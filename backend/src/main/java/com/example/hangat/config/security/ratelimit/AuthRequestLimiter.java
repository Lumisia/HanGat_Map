package com.example.hangat.config.security.ratelimit;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.config.security.token.TokenHasher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 로그인과 인증 메일 요청 횟수를 이메일·IP별로 제한한다.
 * 키에는 원본 이메일이나 IP 대신 SHA-256 해시를 사용한다.
 */
@Component
public class AuthRequestLimiter {

    // ────────────────────────── 제한 정책 ──────────────────────────

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    private static final Duration EMAIL_WINDOW = Duration.ofMinutes(10);
    private static final int LOGIN_PER_EMAIL = 10;
    private static final int LOGIN_PER_IP = 30;
    private static final int EMAIL_PER_ADDRESS = 3;
    private static final int EMAIL_PER_IP = 10;
    private static final int MAX_TRACKED_KEYS = 20_000;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    // ────────────────────────── 요청 검사 ──────────────────────────

    public void checkLogin(String clientIp, String email) {
        check("login:email", EmailNormalizer.normalize(email), LOGIN_PER_EMAIL, LOGIN_WINDOW);
        check("login:ip", clientIp, LOGIN_PER_IP, LOGIN_WINDOW);
    }

    /** 공용 계정의 이메일 제한으로 다른 심사위원까지 차단하지 않도록 IP별로 제한한다. */
    public void checkDemoLogin(String clientIp) {
        check("demo-login:ip", clientIp, 10, LOGIN_WINDOW);
    }

    public void checkEmailRequest(String action, String clientIp, String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        check(action + ":email", normalizedEmail, EMAIL_PER_ADDRESS, EMAIL_WINDOW);
        check(action + ":ip", clientIp, EMAIL_PER_IP, EMAIL_WINDOW);
    }

    // ────────────────────────── 카운터 관리 ──────────────────────────

    private void check(String scope, String subject, int limit, Duration window) {
        long now = System.currentTimeMillis();
        String key = scope + ':' + TokenHasher.hash(subject == null ? "unknown" : subject);
        removeExpiredIfNeeded(now);

        if (counters.size() >= MAX_TRACKED_KEYS && !counters.containsKey(key)) {
            throw new BaseException(BaseResponseStatus.TOO_MANY_REQUESTS);
        }

        AtomicBoolean rejected = new AtomicBoolean(false);
        counters.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAtMillis() <= now) {
                return new WindowCounter(1, now + window.toMillis());
            }
            if (current.count() >= limit) {
                rejected.set(true);
                return current;
            }
            return new WindowCounter(current.count() + 1, current.expiresAtMillis());
        });

        if (rejected.get()) {
            throw new BaseException(BaseResponseStatus.TOO_MANY_REQUESTS);
        }
    }

    private void removeExpiredIfNeeded(long now) {
        if (counters.size() < 10_000) {
            return;
        }
        counters.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private record WindowCounter(int count, long expiresAtMillis) {
    }
}
