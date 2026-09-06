package com.example.hangat.common.storage;

import java.io.InputStream;

/**
 * 이미지 저장소 계약 - 로컬 디스크와 MinIO의 파일 작업을 같은 방식으로 호출한다.
 * 저장 키는 서버가 만들고, URL 생성과 접근 권한 검사는 도메인 서비스가 담당한다.
 */
public interface FileStorage extends AutoCloseable {
    // ────────────────────────── 파일 저장 및 조회 ──────────────────────────

    /** 지정한 키로 이미지 원문과 실제 콘텐츠 유형을 저장한다. */
    void put(String key, byte[] bytes, String contentType);

    /** 파일 스트림을 연다. 응답 처리 등 호출한 쪽에서 반드시 닫아야 한다. */
    InputStream open(String key);

    /** 후기에 첨부하기 전 저장소에 파일이 실제로 있는지 확인한다. */
    boolean exists(String key);

    // ────────────────────────── 파일 및 연결 정리 ──────────────────────────

    /** 지정한 파일을 제거한다. 이미 없는 파일의 삭제는 성공으로 처리한다. */
    void delete(String key);

    /** 앱 종료 시 연결 자원을 정리한다. 연결을 소유하지 않는 구현체는 생략한다. */
    @Override
    default void close() throws Exception {}
}
