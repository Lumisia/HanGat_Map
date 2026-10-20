package com.example.hangat.config.security.cookie;

import com.example.hangat.user.model.auth.RefreshToken;
import com.example.hangat.user.model.oauth.OAuthLoginFlow;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 *  인증에 사용하는 HttpOnly 쿠키의 생성과 삭제를 담당한다.
 *
 *  refresh 토큰과 OAuth 진행 토큰의 쿠키 옵션을 한곳에서 관리하고
 *  컨트롤러, OAuth, 성공 처리기마다 보안 설정이 틀려지는걸 방지함.
 */
@Component
public class AuthCookieManager {

    public static final String REFRESH_COOKIE = "hangat_rt";
    public static final String OAUTH_FLOW_COOKIE = "hangat_oauth_flow";
    public static final String RECOVERY_COOKIE = "hangat_recovery";

    public void setRecoveryCookie(HttpServletResponse response, String token) {
        addCookie(response, RECOVERY_COOKIE, token, "/auth/withdrawal", Duration.ofMinutes(10));
    }

    public void clearRecoveryCookie(HttpServletResponse response) {
        addCookie(response, RECOVERY_COOKIE, "", "/auth/withdrawal", Duration.ZERO);
    }

    private final boolean secure;

    public AuthCookieManager(
            @Value("${app.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    // ────────────────────────── Refresh 쿠키 관리 ──────────────────────────

    // refresh 토큰을 /auth 경로의 HttpOnly 쿠키로 발급.
    public void setRefreshCookie(
            HttpServletResponse response,
            String rawRefreshToken) {

        addCookie(
                response,
                REFRESH_COOKIE,
                rawRefreshToken,
                "/auth",
                RefreshToken.ABSOLUTE_TTL
        );
    }

    // refresh 쿠키 삭제.
    public void clearRefreshCookie(
            HttpServletResponse response) {

        addCookie(
                response,
                REFRESH_COOKIE,
                "",
                "/auth",
                Duration.ZERO
        );
    }

    // ────────────────────────── OAuth 진행 쿠키 관리 ──────────────────────────

    // OAuth 토큰을 /auth/oauth 경로의 httpOnly 쿠키로 발급.
    public void setOAuthFlowCookie(
            HttpServletResponse response,
            String rawFlowToken) {

        addCookie(
                response,
                OAUTH_FLOW_COOKIE,
                rawFlowToken,
                "/auth/oauth",
                OAuthLoginFlow.FLOW_TTL
        );
    }

    // OAuth 진행중인 쿠키를 삭제.
    public void clearOAuthFlowCookie(
            HttpServletResponse response) {

        addCookie(
                response,
                OAUTH_FLOW_COOKIE,
                "",
                "/auth/oauth",
                Duration.ZERO
        );
    }

    // ────────────────────────── 쿠키 추가 httpOnly랑 Lax 설정. ──────────────────────────

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            String path,
            Duration maxAge) {

        ResponseCookie cookie = ResponseCookie
                .from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie.toString()
        );
    }
}
