package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.config.mail.WithdrawalMailDispatcher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.user.model.*;
import com.example.hangat.user.model.auth.*;
import com.example.hangat.user.repository.*;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class AccountWithdrawalService {
    private final UserRepository users;
    private final RefreshTokenRepository refresh;
    private final AccountRecoveryTokenRepository recovery;
    private final EntityManager em;
    private final WithdrawalMailDispatcher mail;
    private final Clock clock;
    @Autowired
    public AccountWithdrawalService(UserRepository users, RefreshTokenRepository refresh,
            AccountRecoveryTokenRepository recovery, EntityManager em, WithdrawalMailDispatcher mail) {
        this(users, refresh, recovery, em, mail, Clock.systemUTC());
    }
    AccountWithdrawalService(UserRepository users, RefreshTokenRepository refresh,
            AccountRecoveryTokenRepository recovery, EntityManager em, WithdrawalMailDispatcher mail, Clock clock) {
        this.users=users; this.refresh=refresh; this.recovery=recovery; this.em=em; this.mail=mail; this.clock=clock;
    }
    public record WithdrawalResult(Instant deleteAt) {}
    public record RecoveryDetails(String email, Instant deleteAt) {}
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private BaseException invalid() { return new BaseException(BaseResponseStatus.JWT_INVALID); }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public WithdrawalResult withdraw(Long userId, String email) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::invalid);
        em.flush();
        em.refresh(user);
        if (user.isDemoAccount() || !user.canLogin()
                || !user.getEmail().equals(EmailNormalizer.normalize(email))) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }
        user.withdrawAt(now());
        refresh.findAllActiveForUpdate(userId).forEach(t -> t.revoke(RefreshRevokeReason.SUSPENDED));
        recovery.deleteAllByUserId(userId);
        em.createNativeQuery("UPDATE course_generation_jobs SET status='FAILED', error_code='ACCOUNT_WITHDRAWN', lease_token=NULL, lease_until=NULL WHERE user_id=:id AND status IN ('QUEUED','RUNNING')")
                .setParameter("id", userId).executeUpdate();
        em.createNativeQuery("UPDATE course_shares SET active = false, token = NULL, updated_at = :now WHERE course_id IN (SELECT id FROM courses WHERE user_id = :id)")
                .setParameter("now", now()).setParameter("id", userId).executeUpdate();
        em.createNativeQuery("UPDATE user_notification_settings SET trip_course_id = NULL, trip_title = NULL, trip_start_date = NULL, trip_end_date = NULL, trip_confirmed_at = NULL, trip_version = trip_version + 1 WHERE user_id = :id")
                .setParameter("id", userId).executeUpdate();
        Instant deleteAt = deleteAt(user);
        mail.sendAfterCommit(user.getEmail(), deleteAt);
        return new WithdrawalResult(deleteAt);
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String issue(User authenticatedUser) {
        User user = users.findByIdForUpdate(authenticatedUser.getId()).orElseThrow(this::invalid);
        em.flush();
        em.refresh(user);
        requireRecoverable(user);
        String raw = TokenHasher.generateToken();
        recovery.save(new AccountRecoveryToken(user.getId(), TokenHasher.hash(raw), now().plusMinutes(10)));
        return raw;
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public RecoveryDetails details(String raw) {
        User user = validate(raw);
        return new RecoveryDetails(user.getEmail(), deleteAt(user));
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancel(String raw) {
        User user = validate(raw);
        user.cancelWithdrawal();
        recovery.deleteAllByUserId(user.getId());
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void decline(String raw) {
        if (raw == null || raw.isBlank()) return;
        AccountRecoveryToken token = recovery.findByTokenHash(TokenHasher.hash(raw)).orElse(null);
        if (token == null) return;
        users.findByIdForUpdate(token.getUserId());
        recovery.findByTokenHash(TokenHasher.hash(raw)).ifPresent(recovery::delete);
    }
    private User validate(String raw) {
        if (raw == null || raw.isBlank()) throw invalid();
        String hash = TokenHasher.hash(raw);
        AccountRecoveryToken candidate = recovery.findByTokenHash(hash).orElseThrow(this::invalid);
        User user = users.findByIdForUpdate(candidate.getUserId()).orElseThrow(this::invalid);
        em.flush();
        em.refresh(user);
        // Re-query after taking the shared account lock: another cancellation may have consumed it.
        AccountRecoveryToken token = recovery.findByTokenHash(hash).orElseThrow(this::invalid);
        if (!now().isBefore(token.getExpiresAt())) throw invalid();
        requireRecoverable(user);
        return user;
    }
    private void requireRecoverable(User user) {
        if (user.getStatus() != UserStatus.WITHDRAWN || user.getWithdrawnAt() == null
                || !clock.instant().isBefore(deleteAt(user))) throw invalid();
    }
    private Instant deleteAt(User user) { return user.getWithdrawnAt().toInstant(ZoneOffset.UTC).plus(Duration.ofDays(30)); }
}
