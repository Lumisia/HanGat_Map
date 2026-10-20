package com.example.hangat.notification.repository.inbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** SSE 갱신 신호의 대기열 조회와 전송 후 삭제를 담당한다. */
@Repository
@RequiredArgsConstructor
public class NotificationOutboxRepository {

    private final JdbcTemplate jdbc;

    /** 알림 번호 순서로 한 번에 처리할 전송 신호를 조회한다. */
    public List<OutboxEntry> findPending(int limit) {
        return jdbc.query("""
                SELECT notification_id, user_id
                FROM notification_outbox
                ORDER BY notification_id
                LIMIT ?
                """,
                (rs, rowNum) -> new OutboxEntry(rs.getLong("notification_id"), rs.getLong("user_id")),
                limit
        );
    }

    /** 처리한 신호만 제거하며 알림 본문은 보존한다. */
    public void deleteByNotificationId(long notificationId) {
        jdbc.update("""
                DELETE FROM notification_outbox
                WHERE notification_id = ?
                """, notificationId);
    }

    /** 전송에 필요한 알림 번호와 사용자 번호. */
    public record OutboxEntry(long notificationId, long userId) {
    }
}
