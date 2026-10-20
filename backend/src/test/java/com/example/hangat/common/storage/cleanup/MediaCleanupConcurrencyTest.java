package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.*;
import com.example.hangat.review.service.ReviewPhotoService;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class MediaCleanupConcurrencyTest {
    @Autowired UserRepository users;
    @Autowired MediaCleanupRepository repository;
    @Autowired PlatformTransactionManager manager;
    @Autowired ReviewPhotoService photos;
    @MockitoBean(name="imageStorage") FileStorage apiStorage;

    @Test void attachmentWaitsForCleanupAndRejectsRemovedObject() throws Exception {
        var user = users.saveAndFlush(User.signUpWithSocial(UUID.randomUUID()+"@test.local",UUID.randomUUID().toString()));
        String key = "reviews/"+user.getId()+"/12345678-1234-1234-1234-123456789abc.jpg";
        var now = Instant.now();
        var image = new StoredImage(key, now.minus(Duration.ofDays(3)), "etag");
        repository.saveAndFlush(new MediaCleanupCandidate(image, now.minus(Duration.ofDays(2))));
        var storage = mock(MinioFileStorage.class);
        var enteredDelete = new CountDownLatch(1);
        var releaseDelete = new CountDownLatch(1);
        var exists = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(apiStorage.exists(key)).thenAnswer(i -> exists.get());
        when(storage.describe(key)).thenReturn(image);
        doAnswer(i -> {
            enteredDelete.countDown();
            if (!releaseDelete.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            exists.set(false);
            return null;
        }).when(storage).delete(key);
        var service = new MediaCleanupService(repository, users, storage, manager, Clock.systemUTC());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cleanup = executor.submit(() -> service.inspect(image, false));
            assertThat(enteredDelete.await(5, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var attach = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                started.countDown();
                return photos.validateAttachments(List.of("/media/"+key),user.getId());
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // 파일은 아직 있지만 정리의 회원 잠금 때문에 첨부는 완료되면 안 된다.
            assertThatThrownBy(() -> attach.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseDelete.countDown();
            assertThat(cleanup.get(5,TimeUnit.SECONDS)).isEqualTo(MediaCleanupService.Outcome.DELETED);
            assertThatThrownBy(() -> attach.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.example.hangat.common.exception.BaseException.class);
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }
    }
}
