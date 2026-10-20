package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.EmailMasker;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.config.mail.AuthMailSender;
import com.example.hangat.config.mail.WelcomeMailDispatcher;
import com.example.hangat.config.security.ratelimit.AuthRequestLimiter;
import com.example.hangat.config.security.token.Purpose;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.config.security.token.VerificationCodeGenerator;
import com.example.hangat.config.security.token.VerificationCodeHasher;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.oauth.AuthProvider;
import com.example.hangat.user.model.oauth.OAuthFlowStep;
import com.example.hangat.user.model.oauth.OAuthLoginFlow;
import com.example.hangat.user.model.oauth.UserSocialAccount;
import com.example.hangat.user.model.dto.AuthInternalDto;
import com.example.hangat.user.model.dto.OAuthDto;
import com.example.hangat.user.repository.OAuthLoginFlowRepository;
import com.example.hangat.user.repository.UserRepository;
import com.example.hangat.user.repository.UserSocialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 계정들은 이메일 코드 발송과 상태 전이를 담당한다.
 * Google은 공급자가 검증한 이메일을 사용한다.
 * Kakao는 사용자가 입력한 이메일에 코드를 보내고,
 * 코드 검증이 끝난 뒤에만 기존 한갓 계정 존재 여부를 확인한다.
 */
@Service
@RequiredArgsConstructor
public class OAuthOnboardingService {

    private static final int MAX_FLOW_TOKEN_LENGTH = 512;

    private final OAuthLoginFlowRepository flowRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final AuthMailSender mailSender;
    private final WelcomeMailDispatcher welcomeMail;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher codeHasher;
    private final EmailMasker emailMasker;
    private final AuthRequestLimiter requestLimiter;
    private final AuthService authService;

    // ────────────────────────── 진행 상태 조회 ──────────────────────────

    // 현재 OAUTH의 가입, 연결 상태를 조회.
    @Transactional(readOnly = true)
    public OAuthDto.FlowResponse getFlow(String rawFlowToken) {

        OAuthLoginFlow flow = findUsableFlow(rawFlowToken);

        return toFlowResponse(flow);
    }

    // ────────────────────────── 인증 코드 발송 ──────────────────────────

    /**
     * 신규 가입용 이메일 인증 코드를 발송한다.
     *
     * Google은 providerEmail을 사용하므로 요청 이메일을 허용하지 않는다.
     * Kakao는 사용자가 입력한 이메일을 사용한다.
     */
    @Transactional
    public OAuthDto.SendCodeResponse sendSignupCode(
            String rawFlowToken,
            OAuthDto.SendSignupCodeRequest request,
            String requestIp) {

        OAuthLoginFlow flow =
                findUsableFlowForUpdate(rawFlowToken);

        requireStep(
                flow,
                OAuthFlowStep.PROFILE_REQUIRED);

        String targetEmail = resolveSignupEmail(flow, request.email());

        String nickname = request.nickname().trim();

        if(userRepository.existsByNickname(nickname)) {
            throw new BaseException(
                    BaseResponseStatus.DUPLICATE_NICKNAME
            );
        }

        /**
         * 구글 신규 가입 화면을 보는 사이
         * 같은 이메일의 한갓 계정이 생성됐을 경우
         */
        if(flow.getProvider() == AuthProvider.GOOGLE) {

            User existingUser = userRepository
                    .findByEmail(targetEmail)
                    .orElse(null);

            if(existingUser != null) {
                ensureProviderCanBeLinked(
                        flow,
                        existingUser
                );

                flow.prepareLinkConfirmation(existingUser);

                return new OAuthDto.SendCodeResponse(
                        OAuthFlowStep.LINK_CONFIRMATION,
                        emailMasker.mask(
                                existingUser.getEmail()
                        ),
                        0,
                        OAuthLoginFlow.MAX_TRY
                );
            }
        }

        requestLimiter.checkEmailRequest(
                "oauth-signup-code",
                requestIp,
                targetEmail
        );

        String rawCode = codeGenerator.generate();

        String codeHash = codeHasher.hash(
                Purpose.OAUTH_SIGNUP,
                flow.getFlowTokenHash(),
                rawCode
        );

        flow.issueSignupCode(
                targetEmail,
                nickname,
                codeHash
        );

        mailSender.sendOAuthCode(
                targetEmail,
                rawCode
        );

        return codeSentResponse();
    }

    /**
     * Google 기존 계정 연결을 사용자가 선택했을 때
     * 기존 한갓 이메일로 인증 코드를 발송한다.
     *
     * 대상 이메일은 요청으로 받지 않고 서버의 OAuth 흐름에서 결정한다.
     */
    @Transactional
    public OAuthDto.SendCodeResponse sendLinkCode(
            String rawFlowToken,
            String requesterIp) {

        OAuthLoginFlow flow =
                findUsableFlowForUpdate(rawFlowToken);

        requireStep(
                flow,
                OAuthFlowStep.LINK_CONFIRMATION
        );

        User targetUser = flow.getTargetUser();

        if (targetUser == null) {
            throw flowError();
        }

        ensureProviderCanBeLinked(
                flow,
                targetUser
        );

        String targetEmail = targetUser.getEmail();

        requestLimiter.checkEmailRequest(
                "oauth-link-code",
                requesterIp,
                targetEmail
        );

        String rawCode = codeGenerator.generate();

        String codeHash = codeHasher.hash(
                Purpose.OAUTH_LINK,
                flow.getFlowTokenHash(),
                rawCode
        );

        flow.issueLinkCode(codeHash);

        mailSender.sendOAuthCode(
                targetEmail,
                rawCode
        );

        return codeSentResponse();
    }

    // ────────────────────────── 인증 코드 검증 ──────────────────────────

    /**
     * 이메일 인증 코드를 검증한다.
     *
     * VerificationCodeHasher가 공백 제거와 대문자 변환을 수행하므로
     * 서비스에서 별도로 코드를 정규화하지 않는다.
     *
     * Google 기존 계정은 코드 확인 후 바로 연결한다.
     * Kakao 기존 계정은 코드 확인 후 연결 동의 단계로 이동한다.
     */
    @Transactional(
            noRollbackFor =
                    InvalidOAuthCodeException.class
    )
    public AuthInternalDto.OAuthVerificationResult
    verifyCode(
            String rawFlowToken,
            OAuthDto.VerifyCodeRequest request) {

        OAuthLoginFlow flow =
                findUsableFlowForUpdate(rawFlowToken);

        if (!flow.isCodeUsable()) {
            throw invalidCode();
        }

        boolean matched = codeHasher.matches(
                flow.getCodePurpose(),
                flow.getFlowTokenHash(),
                request.code(),
                flow.getCodeHash()
        );

        if (!matched) {
            flow.addFailedAttempt();
            throw new InvalidOAuthCodeException();
        }

        flow.markEmailVerified();

        /*
         * Google 기존 계정은 코드 발송 전에
         * 사용자가 계정 연결에 이미 동의했다.
         */
        if (flow.getCodePurpose()
                == Purpose.OAUTH_LINK) {

            AuthInternalDto.LoginResult loginResult =
                    completeLinkAndLogin(flow);

            return AuthInternalDto
                    .OAuthVerificationResult
                    .loginCompleted(loginResult);
        }

        /**
         * Kakao는 이 시점에서 처음으로 기존 계정을 조회한다.
         *
         * Google 신규 가입을 진행하는 사이
         * 동일 이메일의 계정이 만들어진 경쟁 상황도 함께 처리한다.
         */
        User existingUser = userRepository
                .findByEmail(flow.getTargetEmail())
                .orElse(null);

        if (existingUser != null) {
            ensureProviderCanBeLinked(
                    flow,
                    existingUser
            );

            flow.markVerifiedLinkConfirmation(
                    existingUser
            );

            return AuthInternalDto
                    .OAuthVerificationResult
                    .linkConfirmation(
                            emailMasker.mask(
                                    existingUser.getEmail()
                            )
                    );
        }

        AuthInternalDto.LoginResult loginResult =
                completeSignupAndLogin(flow);

        return AuthInternalDto
                .OAuthVerificationResult
                .loginCompleted(loginResult);
    }

    // ────────────────────────── 연결 완료·취소 ──────────────────────────

    /**
     * 이메일 인증을 마친 사용자가 기존 계정 연결을 최종 승인한다.
     *
     * 주 흐름은 Kakao지만 Google 가입 도중 동일 이메일 계정이 생긴 경우에도 사용한다.
     * 이미 통과한 이메일 인증을 재사용하므로 코드를 다시 발송하지 않는다.
     */
    @Transactional
    public AuthInternalDto.LoginResult completeLink(
            String rawFlowToken) {

        OAuthLoginFlow flow =
                findUsableFlowForUpdate(rawFlowToken);

        requireStep(
                flow,
                OAuthFlowStep.VERIFIED_LINK_CONFIRMATION
        );

        if (!flow.isEmailVerified()) {
            throw flowError();
        }

        return completeLinkAndLogin(flow);
    }

    /**
     * 사용자가 소셜 가입 또는 기존 계정 연결을 취소한다.
     *
     * 쿠키가 없거나 이미 끝난 흐름이어도 취소 요청 자체는 성공으로 처리한다.
     */
    @Transactional
    public void cancel(String rawFlowToken) {
        if (!isFlowTokenFormatValid(rawFlowToken)) {
            return;
        }

        String flowTokenHash =
                TokenHasher.hash(rawFlowToken);

        flowRepository
                .findByFlowTokenHashForUpdate(
                        flowTokenHash
                )
                .filter(OAuthLoginFlow::isUsable)
                .ifPresent(OAuthLoginFlow::cancel);
    }

    // ────────────────────────── 가입·계정 연결 처리 ──────────────────────────

    /**
     * 이메일 인증을 마친 신규 사용자를 만들고
     * 소셜 계정 연결과 자동 로그인을 완료한다.
     */
    private AuthInternalDto.LoginResult
    completeSignupAndLogin(OAuthLoginFlow flow) {

        if (flow.getTargetEmail() == null
                || flow.getNickname() == null
                || !flow.isEmailVerified()) {
            throw flowError();
        }

        if (userRepository.existsByEmail(
                flow.getTargetEmail())) {

            throw new BaseException(
                    BaseResponseStatus.DUPLICATE_EMAIL
            );
        }

        if (userRepository.existsByNickname(
                flow.getNickname())) {

            throw new BaseException(
                    BaseResponseStatus.DUPLICATE_NICKNAME
            );
        }

        if (socialAccountRepository
                .findByProviderAndProviderUid(
                        flow.getProvider(),
                        flow.getProviderUid()
                )
                .isPresent()) {

            throw new BaseException(
                    BaseResponseStatus
                            .SOCIAL_PROVIDER_ALREADY_LINKED
            );
        }

        User user = User.signUpWithSocial(
                flow.getTargetEmail(),
                flow.getNickname()
        );

        userRepository.saveAndFlush(user);

        UserSocialAccount socialAccount =
                UserSocialAccount.link(
                        user,
                        flow.getProvider(),
                        flow.getProviderUid(),
                        flow.getTargetEmail()
                );

        socialAccountRepository
                .saveAndFlush(socialAccount);

        flow.complete();

        // 소셜 가입은 인증 링크 단계가 없다. 계정이 생긴 이 지점이 곧 가입 완료다.
        welcomeMail.sendAfterCommit(user.getEmail(), user.getNickname());

        return authService.loginSocial(user);
    }

    /**
     * 이메일 소유권 확인이 끝난 기존 사용자에게
     * 소셜 계정을 연결하고 자동 로그인한다.
     */
    private AuthInternalDto.LoginResult
    completeLinkAndLogin(OAuthLoginFlow flow) {

        User targetUser = flow.getTargetUser();

        if (targetUser == null
                || flow.getTargetEmail() == null
                || !flow.isEmailVerified()) {
            throw flowError();
        }

        String targetEmail = EmailNormalizer.normalize(
                targetUser.getEmail()
        );

        if (!targetEmail.equals(
                flow.getTargetEmail())) {
            throw flowError();
        }

        UserSocialAccount existingConnection =
                socialAccountRepository
                        .findByProviderAndProviderUid(
                                flow.getProvider(),
                                flow.getProviderUid()
                        )
                        .orElse(null);

        /*
         * 동일한 provider UID 연결이 동시 요청으로 먼저 완료된 경우다.
         */
        if (existingConnection != null) {
            if (!existingConnection
                    .getUser()
                    .getId()
                    .equals(targetUser.getId())) {

                throw new BaseException(
                        BaseResponseStatus
                                .SOCIAL_PROVIDER_ALREADY_LINKED
                );
            }

            flow.complete();

            return authService.loginSocial(
                    targetUser
            );
        }

        ensureProviderCanBeLinked(
                flow,
                targetUser
        );

        /**
         * PENDING 사용자는 이번 코드로 이메일 소유권을 확인했으므로 활성화한다.
         *
         * SUSPENDED와 WITHDRAWN 사용자는 User.verifyEmail()로 활성화되지 않는다.
         */
        boolean firstVerification = !targetUser.isEmailVerified();
        targetUser.verifyEmail();

        if (!targetUser.canLogin()) {
            throw new BaseException(
                    targetUser
                            .getStatus()
                            .getLoginDeniedStatus()
            );
        }

        /*
         * 이미 쓰고 있던 계정에 공급자를 하나 더 붙이는 것은 가입이 아니다.
         * PENDING 이던 계정이 이번 코드로 처음 열렸을 때만 환영 메일을 보낸다.
         * 위 canLogin() 검사보다 뒤에 둔다 - 정지·탈퇴 계정에 환영 메일이 나가면 안 된다.
         */
        if (firstVerification) {
            welcomeMail.sendAfterCommit(targetUser.getEmail(), targetUser.getNickname());
        }

        UserSocialAccount socialAccount =
                UserSocialAccount.link(
                        targetUser,
                        flow.getProvider(),
                        flow.getProviderUid(),
                        flow.getTargetEmail()
                );

        socialAccountRepository
                .saveAndFlush(socialAccount);

        flow.complete();

        return authService.loginSocial(
                targetUser
        );
    }

    /**
     * 기존 사용자가 같은 공급자의 다른 계정을 이미 연결했는지 확인한다.
     */
    private void ensureProviderCanBeLinked(
            OAuthLoginFlow flow,
            User targetUser) {

        boolean alreadyLinked =
                socialAccountRepository
                        .existsByUserIdAndProvider(
                                targetUser.getId(),
                                flow.getProvider()
                        );

        if (alreadyLinked) {
            throw new BaseException(
                    BaseResponseStatus
                            .SOCIAL_PROVIDER_ALREADY_LINKED
            );
        }
    }

    // ────────────────────────── 이메일 결정·흐름 조회 ──────────────────────────

    /**
     * 공급자별로 이메일 인증 대상을 결정한다.
     */
    private String resolveSignupEmail(
            OAuthLoginFlow flow,
            String requestedEmail) {

        if (flow.getProvider()
                == AuthProvider.GOOGLE) {

            /*
             * Google 이메일은 공급자가 검증한 값을 고정으로 사용한다.
             */
            if (requestedEmail != null
                    && !requestedEmail.isBlank()) {
                throw requestError();
            }

            if (flow.getProviderEmail() == null) {
                throw flowError();
            }

            return flow.getProviderEmail();
        }

        /*
         * Kakao는 공급자 이메일을 사용할 수 없으므로
         * 사용자가 직접 입력한 이메일이 필요하다.
         */
        if (requestedEmail == null
                || requestedEmail.isBlank()) {
            throw requestError();
        }

        return EmailNormalizer.normalize(
                requestedEmail
        );
    }

    /**
     * 상태를 변경하지 않는 OAuth 흐름 조회.
     */
    private OAuthLoginFlow findUsableFlow(
            String rawFlowToken) {

        if (!isFlowTokenFormatValid(rawFlowToken)) {
            throw flowError();
        }

        OAuthLoginFlow flow = flowRepository
                .findByFlowTokenHash(
                        TokenHasher.hash(rawFlowToken)
                )
                .orElseThrow(
                        OAuthOnboardingService::flowError
                );

        if (!flow.isUsable()) {
            throw flowError();
        }

        return flow;
    }

    /**
     * 상태를 변경하기 위해 비관적 잠금으로 OAuth 흐름을 조회한다.
     */
    private OAuthLoginFlow findUsableFlowForUpdate(
            String rawFlowToken) {

        if (!isFlowTokenFormatValid(rawFlowToken)) {
            throw flowError();
        }

        OAuthLoginFlow flow = flowRepository
                .findByFlowTokenHashForUpdate(
                        TokenHasher.hash(rawFlowToken)
                )
                .orElseThrow(
                        OAuthOnboardingService::flowError
                );

        if (!flow.isUsable()) {
            throw flowError();
        }

        return flow;
    }

    private boolean isFlowTokenFormatValid(
            String rawFlowToken) {

        return rawFlowToken != null
                && !rawFlowToken.isBlank()
                && rawFlowToken.length()
                <= MAX_FLOW_TOKEN_LENGTH;
    }

    private void requireStep(
            OAuthLoginFlow flow,
            OAuthFlowStep expectedStep) {

        if (flow.getStep() != expectedStep) {
            throw flowError();
        }
    }

    // ────────────────────────── 응답 변환 ──────────────────────────

    /**
     * 내부 OAuthLoginFlow를 비밀값이 없는 외부 응답 DTO로 변환한다.
     */
    private OAuthDto.FlowResponse toFlowResponse(
            OAuthLoginFlow flow) {

        String providerEmail = null;
        String maskedExistingEmail = null;

        if (flow.getProvider()
                == AuthProvider.GOOGLE
                && flow.getTargetUser() == null) {

            providerEmail = flow.getProviderEmail();
        }

        boolean linkConfirmation =
                flow.getStep()
                        == OAuthFlowStep.LINK_CONFIRMATION
                        || flow.getStep()
                        == OAuthFlowStep
                        .VERIFIED_LINK_CONFIRMATION;

        if (linkConfirmation
                && flow.getTargetUser() != null) {

            maskedExistingEmail =
                    emailMasker.mask(
                            flow.getTargetUser().getEmail()
                    );
        }

        return new OAuthDto.FlowResponse(
                flow.getProvider(),
                flow.getStep(),
                providerEmail,
                maskedExistingEmail
        );
    }

    private OAuthDto.SendCodeResponse
    codeSentResponse() {

        return new OAuthDto.SendCodeResponse(
                OAuthFlowStep.CODE_REQUIRED,
                null,
                OAuthLoginFlow.CODE_TTL.toMillis(),
                OAuthLoginFlow.MAX_TRY
        );
    }

    // ────────────────────────── OAuth 예외 생성 ──────────────────────────

    private static BaseException requestError() {
        return new BaseException(
                BaseResponseStatus.REQUEST_ERROR
        );
    }

    private static BaseException flowError() {
        return new BaseException(
                BaseResponseStatus.OAUTH_FLOW_INVALID
        );
    }

    private static BaseException invalidCode() {
        return new BaseException(
                BaseResponseStatus.OAUTH_CODE_INVALID
        );
    }

    // 잘못된 코드의 attemptCount가 예외와 함께 롤백되는 것을 방지한다.
    private static final class InvalidOAuthCodeException
            extends BaseException {

        private InvalidOAuthCodeException() {
            super(
                    BaseResponseStatus.OAUTH_CODE_INVALID
            );
        }
    }
}
