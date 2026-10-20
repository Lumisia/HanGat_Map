package com.example.hangat.notification.model;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Notification {

    public record NotificationDto(
            String id,
            String type,
            String title,
            String message,
            String targetType,
            String targetId,
            Instant createdAt,
            Instant readAt
    ) {
    }

    public record NotificationPage(
            List<NotificationDto> items,
            String nextCursor,
            long unreadCount
    ) {
    }

    /** 알림 내역의 번호 기반 페이지. 헤더의 기존 커서 API와 분리한다. */
    public record InboxPage(List<NotificationDto> items, int number, int totalPages,
                            long totalElements, long unreadCount) { }

    /** 읽음·삭제 이후 전역 배지에 반영할 미확인 개수. */
    public record InboxResult(long unreadCount) { }

    /** 커밋 완료 후 다른 탭의 알림함을 동기화하기 위한 신호. */
    public record InboxChanged(Long userId) { }
}
