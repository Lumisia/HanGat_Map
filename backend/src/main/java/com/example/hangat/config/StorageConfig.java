package com.example.hangat.config;

import com.example.hangat.common.storage.FileStorage;
import com.example.hangat.common.storage.LocalFileStorage;
import com.example.hangat.common.storage.MinioFileStorage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이미지 저장소 설정 - 실행 환경에 맞는 저장 구현체를 선택한다.
 * 신규 업로드 저장소와 기존 PVC 사진 조회용 저장소를 구분해서 등록한다.
 */
@Configuration
public class StorageConfig {

    // ────────────────────────── 로컬 및 기존 사진 저장소 ──────────────────────────

    /** 운영에서도 이전 PVC 사진을 읽을 수 있도록 로컬 저장소를 유지한다. */
    @Bean("localImageStorage")
    public LocalFileStorage localFileStorage(
            @Value("${app.upload-dir:uploads/reviews}") String directory) {
        // 기존 사진 이전이 완료되기 전까지 이 빈과 PVC를 함께 유지한다.

        return new LocalFileStorage(directory);
    }

    // ────────────────────────── 신규 업로드 저장소 선택 ──────────────────────────

    /** 기본은 로컬 저장이며, prod의 minio 설정은 환경변수 네 개를 모두 요구한다. */
    @Bean(name = "imageStorage", destroyMethod = "close")
    public FileStorage imageStorage(
            @Qualifier("localImageStorage") LocalFileStorage local,
            @Value("${app.storage.type:local}") String type,
            @Value("${app.storage.minio.endpoint:}") String endpoint,
            @Value("${app.storage.minio.bucket:}") String bucket,
            @Value("${app.storage.minio.access-key:}") String accessKey,
            @Value("${app.storage.minio.secret-key:}") String secretKey) {
        if ("local".equals(type)) return local;
        if (!"minio".equals(type)) throw new IllegalStateException("지원하지 않는 app.storage.type입니다.");
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("MinIO 환경변수 4개가 모두 필요합니다.");
        }
        return new MinioFileStorage(endpoint, bucket, accessKey, secretKey);
    }
}
