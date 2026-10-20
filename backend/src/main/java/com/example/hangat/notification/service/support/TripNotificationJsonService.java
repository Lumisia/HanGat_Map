package com.example.hangat.notification.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 비교 상태 JSON 변환. 깨진 상태를 빈 데이터로 숨기지 않는다. */
@Component
@RequiredArgsConstructor
public class TripNotificationJsonService {

    private final ObjectMapper mapper;

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("알림 비교 상태를 저장할 수 없습니다.", e);
        }
    }

    public <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("알림 비교 상태를 읽을 수 없습니다.", e);
        }
    }
}
