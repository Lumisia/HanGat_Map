package com.example.hangat.review.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.storage.FileStorage;
import com.example.hangat.common.storage.ImageValidator;
import com.example.hangat.common.storage.LocalFileStorage;
import com.example.hangat.review.model.ReviewStatus;
import com.example.hangat.review.model.ReviewPhotosDeleted;
import com.example.hangat.review.repository.ReviewImageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 후기 사진 처리 - 업로드 검증, 첨부 소유권, 공개 조회와 파일 삭제를 담당한다.
 * MinIO 주소 대신 백엔드 조회 URL을 반환하며 미첨부 사진은 업로더만 읽을 수 있다.
 */
@Service
@Slf4j
public class ReviewPhotoService {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern KEY = Pattern.compile(
            "reviews/([1-9][0-9]*)/(" + UUID_PATTERN + "\\.(?:jpg|png|webp))");
    private static final Pattern LEGACY = Pattern.compile(UUID_PATTERN + "\\.(?:jpg|jpeg|png|webp)");

    private final FileStorage storage;
    private final LocalFileStorage local;
    private final ImageValidator validator;
    private final ReviewImageRepository images;

    /** DB에 함께 기록할 전체 저장 키와 브라우저용 상대 URL이다. */
    public record Attachment(String key, String url) {}

    /** 같은 저장소 타입의 빈이 둘이므로 Qualifier를 보존하는 생성자를 직접 유지한다. */
    public ReviewPhotoService(@Qualifier("imageStorage") FileStorage storage,
                              @Qualifier("localImageStorage") LocalFileStorage local,
                              ImageValidator validator, ReviewImageRepository images) {
        this.storage = storage;
        this.local = local;
        this.validator = validator;
        this.images = images;
    }

    // ────────────────────────── 사진 업로드 및 첨부 검증 ──────────────────────────

    /** 검증된 파일만 사용자별 UUID 키로 저장하며 일부 실패 시 이미 저장한 파일을 정리한다. */
    public List<String> upload(List<MultipartFile> files, Long userId) {
        if (userId == null || userId <= 0) throw new BaseException(BaseResponseStatus.LOGIN_REQUIRED);
        if (files == null || files.isEmpty()) throw invalid();
        if (files.size() > ReviewService.MAX_IMAGES) {
            throw new BaseException(BaseResponseStatus.REVIEW_TOO_MANY_IMAGES);
        }

        // 하나라도 잘못된 파일이면 저장하기전 전체 요청을 거절함.
        var validated = files.stream().map(validator::validate).toList();

        List<String> keys = new ArrayList<>();

        try {
            for (var file : validated) {
                String key = "reviews/" + userId
                        + "/" + UUID.randomUUID() + "." + file.extension();
                keys.add(key);
                storage.put(key, file.bytes(), file.contentType());
            }
            return keys.stream().map(key -> "/media/" + key).toList();
        } catch (RuntimeException e) {
            keys.forEach(this::deleteQuietly);
            throw e;
        }
    }

    /** 본인의 실제 업로드 파일만 허용하고 같은 사진의 중복 첨부와 URL 위조를 막는다. */
    public List<Attachment> validateAttachments(List<String> urls, Long userId) {
        // 사진 없는 후기 작성도 기존처럼 허용한다.
        if (urls == null || urls.isEmpty()) return List.of();

        if (userId == null || userId <= 0 || urls.size() > ReviewService.MAX_IMAGES
                || new HashSet<>(urls).size() != urls.size()) throw invalid();

        List<Attachment> result = new ArrayList<>();

        for (String url : urls) {
            if (url == null || !url.startsWith("/media/")) throw invalid();
            String key = url.substring("/media/".length());
            var matcher = KEY.matcher(key);
            if (!matcher.matches() || !matcher.group(1).equals(userId.toString())) throw invalid();
            // 동시 요청 간 중복은 DB의 storage_key UNIQUE 제약이 마지막으로 방어한다.
            if (images.existsByStorageKey(key) || !storage.exists(key)) throw invalid();
            result.add(new Attachment(key, url));
        }
        return result;
    }

    // ────────────────────────── 신규 및 기존 사진 조회 ──────────────────────────

    /** 게시된 사진은 공개하고 미첨부 사진은 본인만 허용한다. 삭제된 사진은 모두 404다. */
    public InputStream open(String key, Long viewerId) {
        var matcher = KEY.matcher(key);
        if (!matcher.matches()) throw notFound();

        var attached = images.findByStorageKey(key);
        if (attached.isPresent()) {
            if (attached.get().getReview().getStatus() != ReviewStatus.ACTIVE) throw notFound();
        } else if (viewerId == null || !matcher.group(1).equals(viewerId.toString())) {
            throw notFound();
        }
        return storage.open(key);
    }

    /** 기존 UUID 파일명은 ACTIVE 후기와 연결된 경우에만 원래 PVC에서 읽는다. */
    public InputStream openLegacy(String filename) {
        if (!LEGACY.matcher(filename).matches()) throw notFound();
        var attached = images.findByStorageKey(filename);
        if (attached.isEmpty() || attached.get().getReview().getStatus() != ReviewStatus.ACTIVE) {
            throw notFound();
        }
        return local.open(filename);
    }

    // ────────────────────────── 커밋 후 파일 정리 ──────────────────────────

    /** DB 변경 확정 이후 호출된다. 파일 삭제 실패가 이미 성공한 후기 삭제를 취소하지 않는다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterReviewDeleted(ReviewPhotosDeleted event) {
        event.keys().forEach(this::deleteQuietly);
    }

    /** 신규 키는 선택된 저장소에서, 기존 평면 키는 로컬에서 삭제하고 실패만 기록한다. */
    private void deleteQuietly(String key) {
        if (key == null) return;
        try {
            if (KEY.matcher(key).matches()) storage.delete(key);
            else if (LEGACY.matcher(key).matches()) local.delete(key);
        } catch (RuntimeException e) {
            // DB에는 삭제된 후기와 키가 남는다. 재시도 배치는 후속 작업.
            log.warn("후기 이미지 정리 실패 key={} type={}", key, e.getClass().getSimpleName());
        }
    }

    /** 서버가 정한 확장자에 맞춰 브라우저 응답 유형을 결정한다. */
    public String contentType(String filename) {
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".webp")) return "image/webp";
        throw notFound();
    }

    // ────────────────────────── 예외 처리 ──────────────────────────

    /** 업로드·첨부 요청의 잘못된 값은 기존 BaseResponse 오류 계약을 따른다. */
    private BaseException invalid() {
        return new BaseException(BaseResponseStatus.REQUEST_ERROR);
    }

    /** 조회 권한이 없거나 삭제된 사진은 존재 여부를 구분해 노출하지 않고 404로 응답한다. */
    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
