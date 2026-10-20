package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.MinioFileStorage;
import com.example.hangat.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

/** 정리 배치만 MinIO 빈을 만든다. 기존 적재 배치는 저장소 자격증명을 요구하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${hangat.batch.job:}' == 'media-cleanup' || '${hangat.batch.job:}' == 'account-media-cleanup'")
public class MediaCleanupConfig {
    @Bean(destroyMethod = "close")
    MinioFileStorage cleanupStorage(@Value("${app.storage.minio.endpoint}") String endpoint,
                                   @Value("${app.storage.minio.bucket}") String bucket,
                                   @Value("${app.storage.minio.access-key}") String accessKey,
                                   @Value("${app.storage.minio.secret-key}") String secretKey) {
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("사진 정리에는 MinIO 설정 4개가 필요합니다.");
        }
        return new MinioFileStorage(endpoint, bucket, accessKey, secretKey);
    }

    @Bean
    MediaCleanupService mediaCleanupService(MediaCleanupRepository repository, UserRepository users,
                                            MinioFileStorage storage, PlatformTransactionManager manager) {
        return new MediaCleanupService(repository, users, storage, manager, Clock.systemUTC());
    }

    @Bean
    MediaCleanupJobService mediaCleanupJobService(MinioFileStorage storage, MediaCleanupService service,
                         @Value("${hangat.media-cleanup.dry-run:true}") boolean dryRun,
                         @Value("${hangat.batch.job}") String job) {
        return new MediaCleanupJobService(storage, service, dryRun, job.equals("account-media-cleanup"));
    }
}
