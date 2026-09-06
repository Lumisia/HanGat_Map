package com.example.hangat.review.model;

import java.util.List;

/**
 * 후기 사진 정리 이벤트 - 삭제가 확정된 후기의 파일 키만 전달한다.
 * 엔티티를 전달하지 않아 커밋 후 지연 로딩에 의존하지 않는다.
 */
public record ReviewPhotosDeleted(List<String> keys) {
    /** 발행 후 원본 목록이 변경돼도 삭제 대상이 달라지지 않도록 복사한다. */
    public ReviewPhotosDeleted {
        keys = List.copyOf(keys);
    }
}
