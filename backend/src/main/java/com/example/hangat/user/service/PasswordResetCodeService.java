package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.config.mail.AuthMailSender;
import com.example.hangat.config.security.token.Purpose;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.config.security.token.VerificationCodeGenerator;
import com.example.hangat.config.security.token.VerificationCodeHasher;
import com.example.hangat.user.model.auth.PasswordResetRequest;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.dto.AuthDto;
import com.example.hangat.user.repository.PasswordResetRequestRepository;
import com.example.hangat.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *  코드 발송, 코드 확인 및 티켓을 발급해주는 역할.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetCodeService {

    private final PasswordResetRequestRepository resetRepository;
    private final UserRepository userRepository;
    private final AuthMailSender mailSender;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher codeHasher;

    // 1단계 - 코드 발송 (똑같은 응답을 보내야 함.)
    @Transactional
    public AuthDto.SendResetCodeResponse sendCode(AuthDto.SendResetCodeRequest request) {
        String requestId = TokenHasher.generateId();

        userRepository.findByEmail(EmailNormalizer.normalize(request.email()))
                .filter(User::canLogin)
                .filter(user -> !user.isDemoAccount())
                .ifPresent(user ->
                        issueCode(user, requestId));

        return new AuthDto.SendResetCodeResponse(
                requestId,
                PasswordResetRequest.CODE_TTL.toMillis(),
                PasswordResetRequest.MAX_ATTEMPTS
        );
    }

    // 2단계 - 코드 확인 (requestId로 행 찾기.)
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    public AuthDto.VerifyResetCodeResponse verifyCode(
            AuthDto.VerifyResetCodeRequest request) {

        PasswordResetRequest reset = resetRepository
                .findByRequestIdForUpdate(request.requestId())
                .orElseThrow(
                        PasswordResetCodeService::requestError
                );

        if(!reset.isCodeUsable() || reset.getUser().isDemoAccount()) {
            throw requestError();
        }

        boolean matched = codeHasher.matches(
                Purpose.PASSWORD_RESET,
                reset.getRequestId(),
                request.code(),
                reset.getTokenHash()
        );
        if(!matched) {
            reset.addFailedAttempt();
            throw new InvalidResetCodeException();
        }

        String ticket = TokenHasher.generateToken();
        reset.markVerified(TokenHasher.hash(ticket));

        // 맞힐 경우 이메일을 보여줌.
        return new AuthDto.VerifyResetCodeResponse(
                ticket,
                maskEmail(reset.getUser().getEmail()),
                PasswordResetRequest.TICKET_TTL.toMillis()
        );
    }

    private void issueCode(User user, String requestId) {
        // 사용자별로 사용 가능한 코드는 하나만 유지한다.
        resetRepository
                .findAllByUserIdAndUsedAtIsNull(user.getId())
                .forEach(PasswordResetRequest::invalidate);

        String rawCode = codeGenerator.generate();

        String codeHash = codeHasher.hash(
                Purpose.PASSWORD_RESET,
                requestId,
                rawCode
        );

        resetRepository.save(
                PasswordResetRequest.issue(
                        user,
                        requestId,
                        codeHash
                )
        );

        mailSender.sendResetCode(user.getEmail(), rawCode);
    }

    // 이메일 중간 가리기
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if(at <= 2) {
            return "**" + email.substring(at);
        }
        return email.substring(0, 2) + "**" + email.substring(at);
    }

    // ────────────────────────── 에러처리 ──────────────────────────

    private static BaseException requestError() {
        return new BaseException(BaseResponseStatus.REQUEST_ERROR);
    }

    // 잘못된 코드의 attemptCount만 롤백하지 않기 위한 전용 예외.
    private static final class InvalidResetCodeException
            extends BaseException {

        private InvalidResetCodeException() {
            super(BaseResponseStatus.REQUEST_ERROR);
        }
    }
}
