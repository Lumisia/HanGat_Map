package com.example.hangat.review.controller;

import com.example.hangat.review.service.ReviewPhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

/**
 * 후기 사진 조회 API - 비공개 저장소의 이미지를 백엔드를 통해 전달한다.
 * 신규 MinIO/로컬 사진과 기존 PVC 사진 모두 서비스의 공개 여부 검사를 거친다.
 */
@RestController
@RequiredArgsConstructor
public class ReviewPhotoController {
    private final ReviewPhotoService photos;

    // ────────────────────────── 신규 사진 조회 ──────────────────────────

    /** 비회원도 게시된 사진을 볼 수 있지만 미첨부 사진은 업로더만 조회한다. */
    @GetMapping("/media/reviews/{ownerId}/{filename}")
    public ResponseEntity<InputStreamResource> get(
            @PathVariable("ownerId") String ownerId,
            @PathVariable("filename") String filename,
            Authentication authentication) {

        Long viewerId = authentication != null
                && authentication.getPrincipal() instanceof Long id ? id : null;
        String type = photos.contentType(filename);
        return response(photos.open("reviews/" + ownerId + "/" + filename,
                viewerId), type);
    }

    // ────────────────────────── 기존 PVC 사진 조회 ──────────────────────────

    /** 기존 DB에 기록된 /uploads/reviews 주소를 변경하지 않고 계속 제공한다. */
    @GetMapping("/uploads/reviews/{filename}")
    public ResponseEntity<InputStreamResource> legacy(
            @PathVariable("filename") String filename) {
        String type = photos.contentType(filename);
        return response(photos.openLegacy(filename), type);
    }

    // ────────────────────────── 이미지 응답 생성 ──────────────────────────

    /** 원문을 스트리밍하고 MIME 추측과 캐시 저장을 막는다. Spring이 응답 후 스트림을 닫는다. */
    private ResponseEntity<InputStreamResource> response(InputStream input, String type) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(type))
                .body(new InputStreamResource(input));
    }
}
