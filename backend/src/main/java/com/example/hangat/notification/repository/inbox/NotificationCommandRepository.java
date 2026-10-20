package com.example.hangat.notification.repository.inbox;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고유 키를 이용한 알림 중복 방지와 발송 신호 저장을 담당한다. */
@Repository
@RequiredArgsConstructor
public class NotificationCommandRepository {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    /** 호출 서비스의 트랜잭션에서 알림 본문과 발송 신호를 함께 저장한다. */
    public void insertWithOutbox(
            Long userId,
            String type,
            String title,
            String message,
            String targetType,
            String targetId,
            String dedupeKey
    ) {
        // 같은 트랜잭션의 JPA 변경이 JDBC 쓰기보다 먼저 DB에 반영되도록 한다.
        entityManager.flush();
        try {
            jdbc.update("""
                INSERT INTO notifications (
                    user_id, type, title, message,
                    target_type, target_id, dedupe_key, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """,
                userId, type, title, message,
                targetType, targetId, dedupeKey
            );
        } catch (DuplicateKeyException duplicate) {
            // 동일 회원·이벤트가 이미 저장됐다면 전송 완료된 SSE 신호도 다시 만들지 않는다.
            // 다른 DB 오류는 그대로 전파해 알림과 호출자의 상태 변경을 함께 롤백한다.
            return;
        }

        // 이번 트랜잭션에서 새로 생성한 알림에만 전송 신호를 붙인다.
        Long notificationId = jdbc.queryForObject("""
                SELECT id
                FROM notifications
                WHERE user_id = ? AND dedupe_key = ?
                FOR UPDATE
                """,
                Long.class,
                userId,
                dedupeKey
        );

        jdbc.update("""
                INSERT INTO notification_outbox (
                    notification_id, user_id, created_at
                )
                VALUES (?, ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE notification_id = notification_id
                """,
                notificationId,
                userId
        );
    }
}
