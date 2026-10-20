package com.example.hangat.user.service;

import com.example.hangat.common.storage.FileStorage;
import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.user.model.User;
import org.springframework.core.io.ClassPathResource;
import com.example.hangat.common.storage.ImageValidator;
import com.example.hangat.user.model.UserStatus;
import com.example.hangat.user.model.dto.UserDto.UserResponse;
import com.example.hangat.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 프로필 사진 저장 / 조회 - 변경은 본인만, 현재 활성 계정의 사진 조회는 공개한다.
 * 공통 이미지 검증과 저장소를 재사용하며 DB 커밋 전에는 기존 파일을 삭제하지 않는다.
 */
@Service
@Slf4j
public class UserProfileImageService {
    private static final Pattern FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)");
    private final UserRepository users;
    private final ImageValidator validator;
    private final FileStorage storage;
    private final TransactionTemplate transaction;

    // 저장소 빈이 둘이므로 Lombok 생성자 대신 Qualifier를 명시한다.
    public UserProfileImageService(UserRepository users, ImageValidator validator,
                                   @Qualifier("imageStorage") FileStorage storage,
                                   PlatformTransactionManager transactionManager) {
        this.users = users;
        this.validator = validator;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactionManager);
        // execute가 반환될 때 커밋이 끝나야 이전 파일을 안전하게 삭제할 수 있다.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ────────────────────────── 사진 교체 ──────────────────────────

    /** 새 파일 저장 → 회원 행 잠금 및 키 교체 → 커밋 성공 후 이전 파일 정리 순서다. */
    public UserResponse upload(Long userId, MultipartFile file) {
        requireActive(userId);
        if (users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).isDemoAccount()) {
            throw new BaseException(BaseResponseStatus.DEMO_PROFILE_IMAGE_LOCKED);
        }
        var image = validator.validate(file);
        String key = "profiles/" + userId + "/" + UUID.randomUUID() + "." + image.extension();
        Saved saved;
        try {
            // 느린 저장소 통신 중에는 DB 행 잠금을 잡지 않는다.
            storage.put(key, image.bytes(), image.contentType());
            saved = transaction.execute(status -> {
                var user = users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                if (!user.canLogin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                String previous = user.getProfileImageKey();
                user.updateProfileImage(key);
                return new Saved(UserResponse.form(user), previous);
            });
        } catch (RuntimeException failure) {
            // DB 장애로 커밋 결과가 불확실하면 현재 참조 여부부터 확인한다.
            // 확인마저 실패한 경우 파일을 남기는 편이 정상 사진을 지우는 것보다 안전하다.
            try {
                boolean referenced = users.findById(userId).map(u -> key.equals(u.getProfileImageKey())).orElse(false);
                if (!referenced) deleteQuietly(key);
            } catch (RuntimeException checkFailure) {
                log.warn("프로필 사진 실패 정리 보류: userId={}, key={}", userId, key);
            }
            throw failure;
        }
        if (saved.previousKey() != null && saved.previousKey().startsWith("profiles/" + userId + "/")) {
            deleteQuietly(saved.previousKey());
        }
        return saved.user();
    }

    // ────────────────────────── 본인 사진 조회 ──────────────────────────

    /** URL의 UUID뿐 아니라 DB의 현재 키도 일치해야 한다. 다른 회원이나 교체 전 사진은 404다. */
    public InputStream open(Long userId, String filename) {
        requireActive(userId);
        return openPublic(userId, filename);
    }

    // ────────────────────────── 공개 사진 조회 ──────────────────────────

    /** 요청 경로와 DB의 현재 키를 함께 검증한다. 미등록·교체 전·탈퇴·정지 사진은 모두 404다. */
    public InputStream openPublic(Long userId, String filename) {
        if (!FILENAME.matcher(filename).matches()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String key = "profiles/" + userId + "/" + filename;
        var user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.isDemoAccount()) {
            if (user.getStatus() != UserStatus.ACTIVE || !User.DEMO_PROFILE_FILENAME.equals(filename)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            try {
                return new ClassPathResource("images/demo-profile-v1.png").getInputStream();
            } catch (java.io.IOException error) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "데모 프로필 이미지를 읽지 못했습니다.", error);
            }
        }
        if (user.getStatus() != UserStatus.ACTIVE || !key.equals(user.getProfileImageKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return storage.open(key);
    }

    /** 이전 액세스 토큰이 남아 있어도 비활성 계정은 사진을 읽거나 변경할 수 없다. */
    private void requireActive(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!users.existsByIdAndStatus(userId, UserStatus.ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    /** 정리 실패가 이미 성공한 사진 변경을 실패로 보이게 하지 않는다. 키만 기록하고 추후 정리한다. */
    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException failure) {
            log.warn("프로필 사진 파일 정리 필요: key={}", key);
        }
    }

    /** DB 트랜잭션을 벗어난 뒤에도 안전하게 사용할 응답과 이전 키다. */
    private record Saved(UserResponse user, String previousKey) {}
}
