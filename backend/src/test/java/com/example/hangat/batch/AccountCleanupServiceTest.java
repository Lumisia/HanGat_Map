package com.example.hangat.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AccountCleanupServiceTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    AccountCleanupService service;
    final Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, email VARCHAR(255), status VARCHAR(20), withdrawn_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE deleted_media_owners(user_id BIGINT PRIMARY KEY, deleted_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE places(id BIGINT PRIMARY KEY, review_count INT, rating_avg DECIMAL(3,2))");
        jdbc.execute("CREATE TABLE reviews(id BIGINT PRIMARY KEY, user_id BIGINT, place_id BIGINT REFERENCES places(id), status VARCHAR(20), rating INT)");
        jdbc.execute("CREATE TABLE review_images(id BIGINT PRIMARY KEY, review_id BIGINT REFERENCES reviews(id))");
        jdbc.execute("CREATE TABLE courses(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id))");
        jdbc.execute("CREATE TABLE course_items(id BIGINT PRIMARY KEY, course_id BIGINT REFERENCES courses(id))");
        jdbc.execute("CREATE TABLE course_item_costs(id BIGINT PRIMARY KEY, course_id BIGINT REFERENCES courses(id), course_item_id BIGINT REFERENCES course_items(id))");
        jdbc.execute("CREATE TABLE course_shares(id BIGINT PRIMARY KEY, course_id BIGINT REFERENCES courses(id))");
        jdbc.execute("CREATE TABLE course_generation_jobs(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), course_id BIGINT REFERENCES courses(id))");
        jdbc.execute("CREATE TABLE notifications(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id))");
        jdbc.execute("CREATE TABLE notification_outbox(notification_id BIGINT PRIMARY KEY REFERENCES notifications(id), user_id BIGINT)");
        for (String table : new String[]{"account_recovery_tokens", "email_verification_tokens", "password_reset_requests", "refresh_tokens", "user_social_accounts", "favorites", "user_notification_settings", "trip_notification_checkpoints"})
            jdbc.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id))");
        jdbc.execute("CREATE TABLE oauth_login_flows(id BIGINT PRIMARY KEY, target_user_id BIGINT REFERENCES users(id), target_email VARCHAR(255), provider_email VARCHAR(255), provider VARCHAR(20), provider_uid VARCHAR(255))");
        jdbc.execute("ALTER TABLE user_social_accounts ADD provider VARCHAR(20)");
        jdbc.execute("ALTER TABLE user_social_accounts ADD provider_uid VARCHAR(255)");
        service = new AccountCleanupService(jdbc, manager, clock, 100);
    }

    void user(long id, String status, String withdrawn) {
        jdbc.update("INSERT INTO users VALUES(?,?,?,?)", id, "user" + id + "@example.com", status, withdrawn == null ? null : LocalDateTime.parse(withdrawn));
    }

    @Test void exactThirtyDayBoundaryAndStatus() {
        user(1, "WITHDRAWN", "2026-08-20T11:59:59.999999");
        user(2, "WITHDRAWN", "2026-08-20T12:00:00");
        user(3, "WITHDRAWN", "2026-08-20T12:00:00.000001");
        user(4, "ACTIVE", "2026-08-01T00:00:00");
        user(5, "WITHDRAWN", null);
        assertThat(service.runBatch().deleted()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class)).containsExactly(3L,4L,5L);
    }

    @Test void deletesDependenciesButPreservesOtherUserAndPublicPlace() {
        user(1,"WITHDRAWN","2026-08-20T12:00:00"); user(2,"ACTIVE",null);
        jdbc.update("INSERT INTO places VALUES(10,3,3)");
        jdbc.update("INSERT INTO reviews VALUES(1,1,10,'ACTIVE',1),(2,2,10,'ACTIVE',5),(3,2,10,'ACTIVE',NULL)");
        jdbc.update("INSERT INTO review_images VALUES(1,1),(2,2)");
        jdbc.update("INSERT INTO courses VALUES(1,1),(2,2)");
        jdbc.update("INSERT INTO course_items VALUES(1,1),(2,2)");
        jdbc.update("INSERT INTO course_item_costs VALUES(1,1,1),(2,2,2)");
        jdbc.update("INSERT INTO course_shares VALUES(1,1),(2,2)");
        jdbc.update("INSERT INTO course_generation_jobs VALUES(1,1,1),(2,2,2)");
        jdbc.update("INSERT INTO notifications VALUES(1,1),(2,2)");
        jdbc.update("INSERT INTO notification_outbox VALUES(1,1),(2,2)");
        for (String table : new String[]{"account_recovery_tokens","email_verification_tokens","password_reset_requests","refresh_tokens","favorites","user_notification_settings","trip_notification_checkpoints"})
            jdbc.update("INSERT INTO " + table + " VALUES(1,1),(2,2)");
        jdbc.update("INSERT INTO user_social_accounts VALUES(1,1,'GOOGLE','first'),(2,2,'GOOGLE','second')");
        jdbc.update("INSERT INTO oauth_login_flows VALUES(1,1,NULL,NULL,'GOOGLE','first'),(2,NULL,'user1@example.com',NULL,'GOOGLE','first'),(3,2,NULL,NULL,'GOOGLE','second')");
        assertThat(service.runBatch().deleted()).isEqualTo(1);
        for (String table : new String[]{"users","courses","course_items","course_item_costs","course_shares","course_generation_jobs","notifications","review_images","favorites","user_social_accounts"})
            assertThat(jdbc.queryForList("SELECT id FROM " + table, Long.class)).as(table).containsExactly(2L);
        assertThat(jdbc.queryForList("SELECT id FROM oauth_login_flows", Long.class)).containsExactly(3L);
        assertThat(jdbc.queryForObject("SELECT review_count FROM places WHERE id=10", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT rating_avg FROM places WHERE id=10", java.math.BigDecimal.class)).isEqualByComparingTo("5.00");
        assertThat(jdbc.queryForList("SELECT user_id FROM deleted_media_owners",Long.class)).containsExactly(1L);
    }

    @Test void failedAccountRollsBackAndDoesNotPreventNextAccount() {
        user(1,"WITHDRAWN","2026-08-20T12:00:00"); user(2,"WITHDRAWN","2026-08-20T12:00:00");
        jdbc.execute("CREATE TABLE unexpected_dependency(user_id BIGINT REFERENCES users(id))");
        jdbc.update("INSERT INTO unexpected_dependency VALUES(1)");
        var result = service.runBatch();
        assertThat(result.deleted()).isEqualTo(1); assertThat(result.failed()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM users",Long.class)).containsExactly(1L);
        assertThat(jdbc.queryForList("SELECT user_id FROM deleted_media_owners",Long.class)).containsExactly(2L);
        jdbc.update("DELETE FROM unexpected_dependency");
        assertThat(service.runBatch().deleted()).isEqualTo(1);
    }

    @Test void boundedRunLeavesRemainingAccountsForNextRun() {
        user(1,"WITHDRAWN","2026-08-20T12:00:00"); user(2,"WITHDRAWN","2026-08-20T12:00:00");
        assertThat(new AccountCleanupService(jdbc, manager, clock, 1).runBatch().deleted()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM users",Long.class)).containsExactly(2L);
    }

    @Test void recoveryWinningUserLockPreventsDeletion() throws Exception {
        user(1,"WITHDRAWN","2026-08-20T12:00:00");
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var recovery = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id=1 FOR UPDATE",Long.class);
                locked.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e){ throw new RuntimeException(e); }
                jdbc.update("UPDATE users SET status='ACTIVE', withdrawn_at=NULL WHERE id=1");
                return true;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var cleanup = pool.submit(() -> service.deleteAccount(1L));
            release.countDown(); recovery.get(5,TimeUnit.SECONDS);
            assertThat(cleanup.get(5,TimeUnit.SECONDS)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=1",String.class)).isEqualTo("ACTIVE");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
