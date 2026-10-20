package com.example.hangat.user.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.user.model.dto.UserDto.UserResponse;
import com.example.hangat.user.service.UserProfileImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;

/**
 * 프로필 사진 API - 변경은 로그인한 본인만, 현재 사진 조회는 비회원도 가능하다.
 * MinIO 버킷은 비공개로 두고 백엔드가 검증한 사진만 스트림으로 전달한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/users")
@Tag(name = "User")
public class UserProfileImageController {
    // UUID마다 별도 URL이다. 교체·탈퇴 이전 사진은 브라우저에 최대 5분 남을 수 있다.
    private static final CacheControl PUBLIC_IMAGE_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();
    private final UserProfileImageService images;

    /** multipart의 file 한 개만 받고 사용자 ID는 요청값이 아닌 인증 정보에서 읽는다. */
    @PutMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "내 프로필 사진 변경", description = "JPEG/PNG/WebP, 최대 5MiB")
    public BaseResponse<UserResponse> upload(@AuthenticationPrincipal Long userId,
                                             @RequestPart("file") MultipartFile file) {
        return BaseResponse.success(images.upload(userId, file));
    }

    /** 이전 프론트의 본인 전용 경로도 계속 지원한다. 이 경로는 인증이 필요하다. */
    @GetMapping("/me/profile-image/{filename}")
    @Operation(summary = "내 프로필 사진 조회")
    public ResponseEntity<InputStreamResource> read(@AuthenticationPrincipal Long userId,
                                                    @PathVariable String filename) {
        return imageResponse(images.open(userId, filename), filename, CacheControl.noStore());
    }

    /** 다른 회원과 비회원도 리뷰 작성자의 현재 사진을 볼 수 있다. 사용자 정보는 반환하지 않는다. */
    @GetMapping("/{userId:[1-9][0-9]*}/profile-image/{filename}")
    @Operation(summary = "공개 프로필 사진 조회", description = "현재 사진만 공개하며 탈퇴·정지·미등록·교체 전 사진은 404")
    public ResponseEntity<InputStreamResource> readPublic(@PathVariable Long userId, @PathVariable String filename) {
        return imageResponse(images.openPublic(userId, filename), filename, PUBLIC_IMAGE_CACHE);
    }

    /** HEAD는 존재·권한과 헤더만 확인하고 파일 스트림을 읽지 않은 채 닫는다. */
    @RequestMapping(value = "/{userId:[1-9][0-9]*}/profile-image/{filename}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headPublic(@PathVariable Long userId, @PathVariable String filename) throws IOException {
        try (var stream = images.openPublic(userId, filename)) {
            return imageHeaders(filename, PUBLIC_IMAGE_CACHE).build();
        }
    }

    /** 공개 UUID 주소는 브라우저 캐시만 허용한다. 스트림은 응답 완료 시 닫힌다. */
    private ResponseEntity<InputStreamResource> imageResponse(InputStream stream, String filename, CacheControl cacheControl) {
        return imageHeaders(filename, cacheControl).body(new InputStreamResource(stream));
    }

    private ResponseEntity.BodyBuilder imageHeaders(String filename, CacheControl cacheControl) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        return ResponseEntity.ok().cacheControl(cacheControl)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(extension.equals("jpg") ? "image/jpeg" : "image/" + extension));
    }
}
