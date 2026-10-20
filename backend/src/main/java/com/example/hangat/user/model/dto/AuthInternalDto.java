package com.example.hangat.user.model.dto;

import com.example.hangat.user.model.oauth.OAuthFlowStep;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 인증 서비스, 컨트롤러와 Security 처리기 사이에서만 사용하는 내부 DTO 모음.
 *
 * refresh 토큰과 OAuth 진행 토큰 원문이 포함될 수 있으므로
 * 일반 API 응답 DTO로 직접 반환하면 안 된다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AuthInternalDto {

    // ────────────────────────── 일반 로그인 내부 결과 ──────────────────────────

    /**
     * 로그인 완료 후 생성된 응답 본문과 refresh 토큰 원문.
     */
    public record LoginResult(
                    TokenDto.LoginResponse body,
                    String rawRefreshToken,
                    String rawRecoveryToken
            ) {
        public LoginResult(TokenDto.LoginResponse body, String rawRefreshToken) {
            this(body, rawRefreshToken, null);
        }
        public boolean recoveryRequired() { return rawRecoveryToken != null; }
    }

    /**
     * refresh 토큰 회전 결과.
     */
    public record ReissueResult(
            TokenDto.AccessTokenResponse body,
            String rawRefreshToken
    ) {
    }

    // ────────────────────────── OAuth 내부 결과 ──────────────────────────

    /**
     * OAuth 성공 처리기에 전달할 내부 결과.
     * loginResult가 있으면 성공이지만, rawFlowToken이 있으면 앞으로 몇 단계를 더 해야한다.
     */
    public record OAuthStartResult(
            LoginResult loginResult,
            String rawFlowToken
    ) {

        public OAuthStartResult {
            boolean hasLoginResult = loginResult != null;
            boolean hasFlowToken = rawFlowToken != null;

            // 두 값 중 반드시 하나만 존재해야 한다.
            if (hasLoginResult == hasFlowToken) {
                throw new IllegalArgumentException(
                        "OAuth 시작 결과의 상태가 올바르지 않습니다."
                );
            }
        }

        public static OAuthStartResult loginCompleted(
                LoginResult loginResult) {

            return new OAuthStartResult(
                    loginResult,
                    null
            );
        }

        public static OAuthStartResult onboarding(
                String rawFlowToken) {

            return new OAuthStartResult(
                    null,
                    rawFlowToken
            );
        }

        public boolean isLoginCompleted() {
            return loginResult != null;
        }
    }

    /**
     * OAuth 이메일 코드 검증 서비스의 내부 결과.
     * 로그인 완료 여부에 따라 컨트롤러가 refresh 쿠키와
     * OAuth 진행 쿠키를 처리할 수 있도록 한다.
     */
    public record OAuthVerificationResult(
            OAuthFlowStep nextStep,
            String maskedExistingEmail,
            LoginResult loginResult
    ) {

        public OAuthDto.VerifyCodeResponse response() {
            return new OAuthDto.VerifyCodeResponse(
                    nextStep,
                    maskedExistingEmail,
                    loginResult == null
                            ? null
                            : loginResult.body()
            );
        }

        public boolean isLoginCompleted() {
            return loginResult != null;
        }

        public static OAuthVerificationResult loginCompleted(
                LoginResult loginResult) {

            return new OAuthVerificationResult(
                    OAuthFlowStep.COMPLETED,
                    null,
                    loginResult
            );
        }

        public static OAuthVerificationResult linkConfirmation(
                String maskedExistingEmail) {

            return new OAuthVerificationResult(
                    OAuthFlowStep.VERIFIED_LINK_CONFIRMATION,
                    maskedExistingEmail,
                    null
            );
        }
    }
}
