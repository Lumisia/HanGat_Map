package com.example.hangat.batch;

import java.time.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** UTC 보관 기한을 잠금 이후 재검증한다. 계정 하나의 실패가 다른 삭제를 롤백하지 않는다. */
@Slf4j
public class AccountCleanupService {
    public record Result(int deleted, int skipped, int failed) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int limit;

    public AccountCleanupService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("ACCOUNT_CLEANUP_INVALID_LIMIT");
        this.jdbc = jdbc;
        this.clock = clock;
        this.limit = limit;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
    }

    public Result runBatch() {
        var ids = jdbc.queryForList("""
                SELECT id FROM users WHERE status='WITHDRAWN' AND withdrawn_at <= ?
                ORDER BY withdrawn_at,id LIMIT ?
                """, Long.class, cutoff(), limit);
        int deleted = 0, skipped = 0, failed = 0;
        for (long id : ids) {
            try {
                if (deleteAccount(id)) deleted++; else skipped++;
            } catch (RuntimeException failure) {
                // SQL/예외 본문과 회원 식별정보는 로그로 내보내지 않는다.
                failed++;
                log.warn("회원 정리 오류 code=ACCOUNT_CLEANUP_RETRY");
            }
        }
        return new Result(deleted, skipped, failed);
    }

    public boolean deleteAccount(long userId) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            // 복구의 UserRepository.findByIdForUpdate와 동일한 users 행을 잠근다.
            var accounts = jdbc.query("SELECT status, withdrawn_at, email FROM users WHERE id=? FOR UPDATE",
                    (rs, row) -> new Account(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3)), userId);
            if (accounts.isEmpty()) return false;
            var account = accounts.get(0);
            if (!"WITHDRAWN".equals(account.status()) || account.withdrawnAt() == null
                    || account.withdrawnAt().isAfter(cutoff())) return false;

            var places = jdbc.queryForList("SELECT DISTINCT place_id FROM reviews WHERE user_id=? ORDER BY place_id", Long.class, userId);
            // 다른 회원의 리뷰 쓰기도 같은 장소 잠금을 사용한다. ID 순서로 교착을 피한다.
            for (long place : places) jdbc.queryForObject("SELECT id FROM places WHERE id=? FOR UPDATE", Long.class, place);
            jdbc.update("DELETE FROM review_images WHERE review_id IN (SELECT id FROM reviews WHERE user_id=?)", userId);
            jdbc.update("DELETE FROM reviews WHERE user_id=?", userId);
            for (long place : places) jdbc.update("""
                    UPDATE places SET
                      review_count=(SELECT COUNT(*) FROM reviews WHERE place_id=? AND status='ACTIVE'),
                      rating_avg=COALESCE((SELECT AVG(rating) FROM reviews WHERE place_id=? AND status='ACTIVE'),0)
                    WHERE id=?
                    """, place, place, place);

            jdbc.update("DELETE FROM notification_outbox WHERE user_id=? OR notification_id IN (SELECT id FROM notifications WHERE user_id=?)",userId,userId);
            deleteOwned("notifications",userId);
            deleteOwned("trip_notification_checkpoints",userId);
            deleteOwned("user_notification_settings",userId);
            deleteOwned("course_generation_jobs",userId);
            jdbc.update("DELETE FROM course_shares WHERE course_id IN (SELECT id FROM courses WHERE user_id=?)",userId);
            jdbc.update("DELETE FROM course_item_costs WHERE course_id IN (SELECT id FROM courses WHERE user_id=?)",userId);
            jdbc.update("DELETE FROM course_items WHERE course_id IN (SELECT id FROM courses WHERE user_id=?)",userId);
            deleteOwned("courses",userId);
            deleteOwned("favorites",userId);
            // 미완료 소셜 흐름은 target_user_id가 없는 경우에도 이메일/연결 UID가 개인정보다.
            jdbc.update("""
                    DELETE FROM oauth_login_flows WHERE target_user_id=? OR target_email=? OR provider_email=?
                    OR EXISTS (SELECT 1 FROM user_social_accounts a WHERE a.user_id=?
                      AND a.provider=oauth_login_flows.provider AND a.provider_uid=oauth_login_flows.provider_uid)
                    """,userId,account.email(),account.email(),userId);
            for (String table : new String[]{"account_recovery_tokens","email_verification_tokens",
                    "password_reset_requests","refresh_tokens","user_social_accounts"}) deleteOwned(table,userId);
            // 외부 파일은 DB 커밋 후 기존 inventory 배치가 재시도한다. 누락 소유자와 구별할 영구 증거.
            jdbc.update("INSERT INTO deleted_media_owners(user_id,deleted_at) VALUES(?,?)",userId,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
            jdbc.update("DELETE FROM users WHERE id=?",userId);
            return true;
        }));
    }

    private LocalDateTime cutoff() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).minusDays(30); }
    private void deleteOwned(String table, long userId) { jdbc.update("DELETE FROM " + table + " WHERE user_id=?",userId); }
    private record Account(String status, LocalDateTime withdrawnAt, String email) {}
}
