package com.example.hangat.common.storage;

import java.time.Instant;

/** 객체를 덮어쓴 경우 이전 정리 후보와 구분하기 위한 메타데이터. */
public record StoredImage(String key, Instant modifiedAt, String etag) {}
