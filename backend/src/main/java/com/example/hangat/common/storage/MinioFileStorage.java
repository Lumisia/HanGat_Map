package com.example.hangat.common.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

/**
 * MinIO 이미지 저장소 - 한갓 전용 비공개 버킷에 객체를 저장하고 읽는다.
 * 접속 키는 서버에서만 사용하며 SDK의 내부 오류 정보는 API 응답에 노출하지 않는다.
 */
public final class MinioFileStorage implements FileStorage {

    private final MinioClient client;
    private final String bucket;

    /** 전용 클라이언트와 제한 시간을 구성한다. 초기화 로직이 있어 생성자를 직접 유지한다. */
    public MinioFileStorage(String endpoint, String bucket, String accessKey, String secretKey) {
        this.bucket = bucket;

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(30))
                .build();

        client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(http, true)
                .build();
    }

    // ────────────────────────── 객체 저장 및 조회 ──────────────────────────

    /** 원문 길이를 SDK에 전달하여 서버가 생성한 전체 키로 업로드한다. */
    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try (var input = new ByteArrayInputStream(bytes)){
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(input, (long) bytes.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw unavailable();
        }
    }


    /** 버킷을 공개하지 않고 백엔드 응답용 스트림을 반환한다. 호출자가 스트림을 닫는다. */
    @Override
    public InputStream open(String key) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (ErrorResponseException e) {
            if (missing(e)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /** 객체 메타데이터로 존재를 검사한다. 권한/접속 오류를 파일 없음으로 숨기지 않는다. */
    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if (missing(e)) return false;

            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    // ────────────────────────── 객체 및 연결 정리 ──────────────────────────

    /** 같은 삭제 요청을 반복할 수 있도록 이미 없는 객체는 성공으로 처리한다. */
    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (ErrorResponseException e) {
            if (!missing(e)) throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /** Spring의 빈 종료 처리에서 호출해 이 클라이언트가 소유한 HTTP 연결을 정리한다. */
    @Override
    public void close() throws Exception {
        client.close();
    }

    // ────────────────────────── 예외 처리 ──────────────────────────

    /** 버킷 없음·접근 거절과 구분하여 객체 자체가 없는 경우만 판단한다. */
    private boolean missing(ErrorResponseException e) {
        String code = e.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
    }

    /** 내부 주소와 자격증명 정보가 응답에 섞이지 않도록 공통 오류만 전달한다. */
    private ResponseStatusException unavailable() {
        // 메세지 요청에는 내부주소나 중요한 값이 있을 수 있기에 전달하지 않음.

        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "이미지 저장소를 사용할 수 없습니다.");
    }
}
