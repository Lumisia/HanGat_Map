package com.example.hangat.review.model;

/**
 * 후기 공개 상태 - DB 행은 유지하고 공개 여부를 구분한다.
 * DELETED 후기는 목록·평점 집계·사진 조회에서 제외한다.
 */
public enum ReviewStatus {
    ACTIVE, DELETED
}
