package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.config.security.password.PasswordHasher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.notification.service.inbox.NotificationService;
import com.example.hangat.user.model.auth.PasswordResetRequest;
import com.example.hangat.user.model.auth.RefreshRevokeReason;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.dto.AuthDto;
import com.example.hangat.user.repository.PasswordResetRequestRepository;
import com.example.hangat.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *  인증이 끝나고 PasswordResetCodeService에서 발급한 비밀번호 재설정 티켓을 확인
 *  그리고 비밀번호 변경 및 세션 폐기해주는 역할.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetRequestRepository resetRepository;
    private final RefreshTokenRepository refreshRepository;
    private final PasswordHasher passwordHasher;
    private final NotificationService notifications;


    /** 새 비밀번호 설정
     * 성공하면 현재 사용 중인 모든 Refresh 토큰을 폐기한다.
     * 비밀번호 변경 후 기존 세션이 유지되지 않도록 하기 위함이다.
     */
    @Transactional
    public void resetPassword(AuthDto.ResetPasswordRequest request) {
        PasswordResetRequest reset = resetRepository
                .findByTicketHashForUpdate(TokenHasher.hash(request.ticket()))
                .orElseThrow(() -> new BaseException(BaseResponseStatus.REQUEST_ERROR));

        if(!reset.isTicketUsable()) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }

        User user = reset.getUser();
        if (user.isDemoAccount()) {
            throw new BaseException(BaseResponseStatus.DEMO_PASSWORD_LOCKED);
        }

        String encodedPassword = passwordHasher.encodeNew(
                request.password(),
                request.passwordConfirm()
        );

        user.changePassword(encodedPassword);
        reset.markUsed();

        notifications.enqueue(
                user.getId(),
                "SECURITY_PASSWORD_CHANGED",
                "비밀번호가 변경됐어요",
                "본인이 변경한 것이 아니라면 계정 보안을 확인해 주세요.",
                "SECURITY",
                null,
                "PASSWORD_CHANGED:" + java.util.UUID.randomUUID()
        );

        refreshRepository.findAllActiveForUpdate(user.getId())
                .forEach(refreshToken ->
                        refreshToken.revoke(RefreshRevokeReason.PASSWORD_RESET));
    }
}
