package com.example.hangat.common.storage;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 업로드 이미지 검증 - 사용자 파일명과 Content-Type을 그대로 신뢰하지 않는다.
 * 실제 디코딩 형식, 파일 크기와 픽셀 수를 확인하고 저장에 필요한 정보를 반환한다.
 */
@Component
public class ImageValidator {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000;

    /** 검증된 원문과 실제 형식이다. 확장자는 서버가 판단한 값으로만 사용한다. */
    public record ValidatedImage(byte[] bytes, String extension, String contentType) {}

    // ────────────────────────── 파일 형식 및 용량 검증 ──────────────────────────

    /** JPEG/PNG/WebP만 허용하며 최대 5MiB, 2천만 픽셀로 디코딩 범위를 제한한다. */
    public ValidatedImage validate(MultipartFile file) {
        // 파일이 없거나 사이즈가 클 경우
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();

        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                ImageReader reader = readers.next();

                // 파일명이 아니라 읽기 가능한 실제 이미지 형식으로 확장자를 정한다.
                try {
                    String extension = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "jpg", "jpeg" -> "jpg";
                        case "png" -> "png";
                        case "webp" -> "webp";
                        default -> throw invalid();
                    };
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    // 압축 파일이 작아도 복원 시 메모리를 과도하게 쓰는 이미지는 거절한다.
                    if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) throw invalid();
                    var decoded = reader.read(0);
                    if (decoded == null) throw invalid();
                    decoded.flush();
                    return new ValidatedImage(bytes, extension,
                            extension.equals("jpg") ? "image/jpeg" : "image/" + extension);
                } finally {
                    // 이미지 리더가 사용하는 디코딩 자원을 요청마다 해제한다.
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    // ────────────────────────── 예외 응답 ──────────────────────────

    /** 내부 디코더 오류 대신 기존 API의 요청 오류 형식으로 응답한다. */
    private BaseException invalid() {
        return new BaseException(BaseResponseStatus.REQUEST_ERROR);
    }
}
