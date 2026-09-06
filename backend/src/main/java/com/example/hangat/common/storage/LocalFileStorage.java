package com.example.hangat.common.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 로컬 이미지 저장소 - 개발 환경의 업로드와 운영의 기존 PVC 사진을 처리한다.
 * 설정된 폴더 내부의 키만 허용하고 동일한 파일을 덮어쓰지 않는다.
 */
public final class LocalFileStorage implements FileStorage {
    private final Path root;

    /** 저장 루트를 절대 경로로 고정한다. 경로 변환이 필요해 생성자를 직접 유지한다. */
    public LocalFileStorage(String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    // ────────────────────────── 저장 경로 검증 ──────────────────────────

    /** 경로 조작이나 저장 루트 바깥 접근을 막고 실제 파일 경로로 변환한다. */
    private Path resolve(String key) {
        if (key == null || !key.matches("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return path;
    }

    // ────────────────────────── 파일 저장 및 조회 ──────────────────────────

    /** 하위 폴더를 만든 뒤 새 파일로만 저장한다. 기존 파일은 덮어쓰지 않는다. */
    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw unavailable();
        }
    }

    /** 일반 파일만 열고, 존재하지 않거나 조회 중 사라진 파일은 404로 처리한다. */
    @Override
    public InputStream open(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IOException e) {
            throw unavailable();
        }
    }

    /** 파일 존재를 확인하며 최종 경로의 심볼릭 링크는 파일로 인정하지 않는다. */
    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key), LinkOption.NOFOLLOW_LINKS);
    }

    // ────────────────────────── 파일 삭제 및 예외 응답 ──────────────────────────

    /** 없는 파일은 무시하므로 삭제 이벤트가 다시 전달되어도 안전하다. */
    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw unavailable();
        }
    }

    /** 디스크의 내부 경로를 노출하지 않고 저장소 오류를 503으로 전달한다. */
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다.");
    }
}
