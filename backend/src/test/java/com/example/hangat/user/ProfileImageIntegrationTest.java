package com.example.hangat.user;

import com.example.hangat.common.storage.FileStorage;
import com.example.hangat.common.storage.LocalFileStorage;
import com.example.hangat.config.security.jwt.JwtProvider;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hangat.user.service.UserProfileImageService;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileImageIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean UserRepository users;
    @Autowired UserProfileImageService images;
    @Autowired PlatformTransactionManager transactionManager;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @MockitoBean(name = "imageStorage") FileStorage storage;
    @TempDir Path directory;
    User owner;
    byte[] png;

    @BeforeEach
    void setup() throws Exception {
        owner = user();
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        png = out.toByteArray();
        // 외부 MinIO 대신 같은 FileStorage 계약의 실제 로컬 파일을 사용한다.
        var local = new LocalFileStorage(directory.toString());
        doAnswer(i -> { local.put(i.getArgument(0), i.getArgument(1), i.getArgument(2)); return null; })
                .when(storage).put(anyString(), any(), anyString());
        when(storage.open(anyString())).thenAnswer(i -> local.open(i.getArgument(0)));
        doAnswer(i -> { local.delete(i.getArgument(0)); return null; }).when(storage).delete(anyString());
    }

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.signUpWithSocial(id + "@test.local", id));
    }

    private String token(User user) { return "Bearer " + jwt.createAccessToken(user.getId()); }

    @Test void 데모_사진은_배포_이미지로_고정되고_직접_업로드도_막힌다() throws Exception {
        User demo = users.saveAndFlush(User.signUpWithSocial("demo@hangatjeju.com", "한갓지도 데모계정"));
        try {
            String publicPath = demo.publicProfileImageUrl();
            assertThat(publicPath).isNotNull();
            byte[] expected;
            try (var stream = new org.springframework.core.io.ClassPathResource("images/demo-profile-v1.png").getInputStream()) {
                expected = stream.readAllBytes();
            }
            mvc.perform(get("/users/me").header("Authorization", token(demo)))
                    .andExpect(jsonPath("$.result.profileImageUrl").value(publicPath));
            mvc.perform(get(publicPath)).andExpect(status().isOk()).andExpect(content().bytes(expected))
                    .andExpect(header().string("Cache-Control", "max-age=300, private"));
            mvc.perform(multipart("/users/me/profile-image")
                            .file(new MockMultipartFile("file", "replacement.png", "image/png", png))
                            .with(r -> { r.setMethod("PUT"); return r; }).header("Authorization", token(demo)))
                    .andExpect(status().isForbidden());
            verify(storage, never()).put(anyString(), any(), anyString());
            verify(storage, never()).open(anyString());
            assertThatThrownBy(() -> demo.updateProfileImage(null)).isInstanceOf(RuntimeException.class);
            mvc.perform(get(publicPath)).andExpect(content().bytes(expected));
            mvc.perform(get("/users/" + owner.getId() + "/profile-image/" + publicPath.substring(publicPath.lastIndexOf('/') + 1)))
                    .andExpect(status().isNotFound());
        } finally { users.deleteById(demo.getId()); }
    }

    private String upload() throws Exception {
        var result = mvc.perform(multipart("/users/me/profile-image")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .with(r -> { r.setMethod("PUT"); return r; })
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.profileImageUrl").isString())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("result").path("profileImageUrl").asText();
    }

    @Test void 업로드한_사진은_본인만_읽고_교체하면_이전_파일이_삭제된다() throws Exception {
        String uploaded = upload();
        // 이전 프론트가 가진 본인 전용 URL도 계속 지원한다.
        String first = "/users/me/profile-image/" + uploaded.substring(uploaded.lastIndexOf('/') + 1);
        mvc.perform(get(first).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(first).header("Authorization", token(user()))).andExpect(status().isNotFound());
        mvc.perform(get(first)).andExpect(status().isUnauthorized());
        String second = upload();
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get(first).header("Authorization", token(owner))).andExpect(status().isNotFound());
        mvc.perform(get(second).header("Authorization", token(owner))).andExpect(content().bytes(png));
        mvc.perform(get("/users/me").header("Authorization", token(owner)))
                .andExpect(jsonPath("$.result.profileImageUrl").value(second));
        try (var paths = Files.walk(directory)) { assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1); }
    }

    @Test void 이미지로_위장한_파일은_기존_사진을_바꾸지_못한다() throws Exception {
        String original = upload();
        mvc.perform(multipart("/users/me/profile-image")
                        .file(new MockMultipartFile("file", "bad.png", "image/png", "not an image".getBytes()))
                        .with(r -> { r.setMethod("PUT"); return r; }).header("Authorization", token(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(original).header("Authorization", token(owner))).andExpect(content().bytes(png));
    }

    @Test void 저장소_업로드_실패는_기존_사진을_보존한다() throws Exception {
        String original = upload();
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(storage).put(anyString(), any(), anyString());
        mvc.perform(multipart("/users/me/profile-image")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .with(r -> { r.setMethod("PUT"); return r; }).header("Authorization", token(owner)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get(original).header("Authorization", token(owner))).andExpect(content().bytes(png));
    }

    @Test void 비회원은_업로드할_수_없다() throws Exception {
        mvc.perform(multipart("/users/me/profile-image")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png))
                        .with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isUnauthorized());
    }

    @Test void DB_커밋_실패시_새_파일만_정리하고_기존_사진을_유지한다() throws Exception {
        String original = upload();
        doAnswer(i -> {
            // 실제 트랜잭션 커밋 시 컬럼 길이 제약 위반을 일으켜 롤백·보상 처리를 검증한다.
            var found = java.util.Optional.ofNullable(entityManager.find(User.class, owner.getId(),
                    jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
            found.orElseThrow().setNickname("x".repeat(51));
            return found;
        }).when(users).findByIdForUpdate(owner.getId());
        assertThatThrownBy(() -> images.upload(owner.getId(), new MockMultipartFile("file", png)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        mvc.perform(get(original).header("Authorization", token(owner))).andExpect(content().bytes(png));
        try (var paths = Files.walk(directory)) { assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1); }
    }

    @Test void 동시에_사진을_교체해도_DB가_가리키는_파일은_남는다() throws Exception {
        var barrier = new CountDownLatch(2);
        var local = new LocalFileStorage(directory.toString());
        doAnswer(i -> {
            local.put(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            barrier.countDown();
            if (!barrier.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("upload timed out");
            return null;
        }).when(storage).put(anyString(), any(), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> images.upload(owner.getId(), new MockMultipartFile("file", png)));
            var second = executor.submit(() -> images.upload(owner.getId(), new MockMultipartFile("file", png)));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            var current = users.findById(owner.getId()).orElseThrow();
            assertThat(local.exists(current.getProfileImageKey())).isTrue();
            try (var paths = Files.walk(directory)) { assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1); }
        } finally { executor.shutdownNow(); }
    }

    @Test void 이전에_시작한_닉네임_수정이_새_사진_키를_덮어쓰지_않는다() throws Exception {
        var read = new CountDownLatch(1);
        var uploaded = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var stale = users.findById(owner.getId()).orElseThrow();
                read.countDown();
                try {
                    if (!uploaded.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("upload timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                stale.setNickname("edited-" + owner.getId());
                return null;
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            String path = upload();
            uploaded.countDown();
            pending.get(10, TimeUnit.SECONDS);
            mvc.perform(get(path).header("Authorization", token(owner))).andExpect(content().bytes(png));
        } finally { uploaded.countDown(); executor.shutdownNow(); }
    }

    @Test void 탈퇴_계정은_이전_토큰으로_사진을_읽거나_바꿀_수_없다() throws Exception {
        String path = upload();
        owner.withdraw();
        users.saveAndFlush(owner);
        String privatePath = "/users/me/profile-image/" + path.substring(path.lastIndexOf('/') + 1);
        mvc.perform(get(privatePath).header("Authorization", token(owner))).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/users/me/profile-image")
                        .file(new MockMultipartFile("file", png))
                        .with(r -> { r.setMethod("PUT"); return r; }).header("Authorization", token(owner)))
                .andExpect(status().isUnauthorized());
    }

    @Test void 공개_프로필은_다른_회원과_비회원도_읽지만_변경할_수_없다() throws Exception {
        String privatePath = upload();
        assertThat(privatePath).startsWith("/users/me/profile-image/");
        String path = privatePath.replace("/users/me/", "/users/" + owner.getId() + "/");
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", "max-age=300, private"));
        mvc.perform(head(path)).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]))
                .andExpect(header().string("Cache-Control", "max-age=300, private"));
        var other = user();
        mvc.perform(get(path).header("Authorization", token(other))).andExpect(content().bytes(png));
        mvc.perform(get(path.replace("/users/" + owner.getId() + "/", "/users/" + other.getId() + "/")))
                .andExpect(status().isNotFound());
        mvc.perform(put(path)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/users/" + owner.getId() + "/profile-image")
                        .file(new MockMultipartFile("file", png)).with(r -> { r.setMethod("PUT"); return r; })
                        .header("Authorization", token(other)))
                .andExpect(status().isNotFound());
        upload();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
    }

    @Test void 리뷰_목록과_작성_응답은_현재_프로필만_제공하며_탈퇴한_사진은_숨긴다() throws Exception {
        String first = upload().replace("/users/me/", "/users/" + owner.getId() + "/");
        Long placeId = new TransactionTemplate(transactionManager).execute(status -> {
            var region = com.example.hangat.map.model.entity.Region.builder()
                    .code("PHOTO").name("사진테스트").displayOrder((byte) 99).build();
            var category = com.example.hangat.map.model.entity.PlaceCategory.builder().code("PHOTO").name("사진테스트").build();
            entityManager.persist(region);
            entityManager.persist(category);
            var place = com.example.hangat.map.model.entity.Place.builder().name("프로필 테스트 장소")
                    .normalizedName("프로필테스트장소").region(region).primaryCategory(category).build();
            entityManager.persist(place);
            return place.getId();
        });
        mvc.perform(post("/places/" + placeId + "/reviews").header("Authorization", token(owner))
                        .contentType("application/json").content("{\"rating\":5,\"content\":\"사진 테스트\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.profileImageUrl").value(first));
        String second = upload().replace("/users/me/", "/users/" + owner.getId() + "/");
        mvc.perform(get("/places/" + placeId + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].profileImageUrl").value(second))
                .andExpect(jsonPath("$.result.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.result.content[0].profileImageKey").doesNotExist());
        mvc.perform(get(first)).andExpect(status().isNotFound());
        var current = users.findById(owner.getId()).orElseThrow();
        current.withdraw();
        users.saveAndFlush(current);
        mvc.perform(get(second)).andExpect(status().isNotFound());
        mvc.perform(get("/places/" + placeId + "/reviews"))
                .andExpect(jsonPath("$.result.content[0].profileImageUrl").isEmpty());
    }
}
