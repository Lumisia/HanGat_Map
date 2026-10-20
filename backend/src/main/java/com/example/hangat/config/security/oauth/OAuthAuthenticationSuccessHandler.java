package com.example.hangat.config.security.oauth;

import com.example.hangat.config.security.cookie.AuthCookieManager;
import com.example.hangat.user.model.dto.AuthInternalDto;
import com.example.hangat.user.service.OAuthLoginService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * 구글이랑 카카오 공급자 인증 성공 이후
 * 한갓 자동 로그인 또는 추가 가입 흐름을 시작한다.
 *
 * JWT, provider access token과 OAuth 진행 토큰은 URL에 넣지 않는다.
 * refresh 토큰과 OAuth 진행 토큰은 HttpOnly 쿠키로만 전달한다.
 */
@Slf4j
@Component
public class OAuthAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthProviderUserMapper userMapper;
    private final OAuthLoginService oAuthLoginService;
    private final AuthCookieManager cookieManager;
    private final String frontendUrl;

    public OAuthAuthenticationSuccessHandler(
            OAuthProviderUserMapper userMapper,
            OAuthLoginService oAuthLoginService,
            AuthCookieManager cookieManager,
            @Value("${app.frontend-url}")
            String frontendUrl) {

        this.userMapper = userMapper;
        this.oAuthLoginService = oAuthLoginService;
        this.cookieManager = cookieManager;
        this.frontendUrl =
                removeTrailingSlash(frontendUrl);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        try {
            if(!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {

                redirectFailure(request, response);
                return;
            }

            OAuthProviderUser providerUser =
                    userMapper.map(oauthToken);

            AuthInternalDto.OAuthStartResult result =
                    oAuthLoginService.start(
                            providerUser
                    );

            invalidateSession(request);
            SecurityContextHolder.clearContext();

            if(result.isLoginCompleted()) {
                if (result.loginResult().recoveryRequired()) {
                    cookieManager.clearRefreshCookie(response);
                    cookieManager.clearOAuthFlowCookie(response);
                    cookieManager.setRecoveryCookie(response, result.loginResult().rawRecoveryToken());
                    response.setHeader("Cache-Control", "no-store");
                    response.sendRedirect(frontendUrl + "/auth/withdrawal");
                    return;
                }
                cookieManager.clearRecoveryCookie(response);
                cookieManager.setRefreshCookie(
                        response,
                        result.loginResult()
                                .rawRefreshToken()
                );

                response.sendRedirect(callbackUrl("login"));

                return;
            }

            cookieManager.setOAuthFlowCookie(response, result.rawFlowToken());

            response.sendRedirect(callbackUrl("onboarding"));
        }catch (RuntimeException e) {

            // 이메일, provider UID, authorization code와 token은 로그에 남기지 않는다.
            log.warn(
                    "OAuth 로그인 후처리 실패 type = {}",
                    e.getClass().getSimpleName()
            );

            redirectFailure(request, response);
        }
    }

    private void redirectFailure(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        invalidateSession(request);
        SecurityContextHolder.clearContext();

        cookieManager.clearOAuthFlowCookie(response);

        response.sendRedirect(callbackUrl("failure"));
    }

    // ────────────────────────── URL 설정이나 공통 관련 ──────────────────────────

    private void invalidateSession(HttpServletRequest request) {

        HttpSession session = request.getSession(false);

        if(session != null) {
            session.invalidate();
        }
    }

    private String callbackUrl(String result) {
        return frontendUrl + "/oauth/callback?result=" + result;
    }
    private static String removeTrailingSlash(String value) {

        if(value.endsWith("/")) {
            return value.substring(0, value.length()-1);
        }

        return value;
    }
}
