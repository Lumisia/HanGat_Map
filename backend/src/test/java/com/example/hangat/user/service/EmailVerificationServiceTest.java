package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.config.mail.AuthMailSender;
import com.example.hangat.config.mail.WelcomeMailDispatcher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.auth.EmailVerificationToken;
import com.example.hangat.user.repository.EmailVerificationTokenRepository;
import com.example.hangat.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인증 링크 처리 - 환영 메일이 가입당 한 번만 나가는지.
 * 환영 메일은 취소할 수 없으므로 "언제 보내는가"가 이 클래스의 계약이다.
 */
class EmailVerificationServiceTest {

    private static final String RAW_TOKEN = "raw-token";

    private final EmailVerificationTokenRepository tokenRepository =
            mock(EmailVerificationTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthMailSender mailSender = mock(AuthMailSender.class);
    private final WelcomeMailDispatcher welcomeMail = mock(WelcomeMailDispatcher.class);
    private final EmailVerificationService service =
            new EmailVerificationService(tokenRepository, userRepository, mailSender, welcomeMail);

    @Test
    void 처음_인증하면_환영_메일이_나간다() {
        User user = User.signUpWithEmail("her@example.com", "encoded", "제주러버");
        givenUsableTokenFor(user);

        service.verify(RAW_TOKEN);

        verify(welcomeMail).sendAfterCommit("her@example.com", "제주러버");
    }

    /**
     * verifyEmail() 은 멱등이라 이미 인증된 계정에도 그냥 통과한다.
     * 그 경로로 환영 메일이 또 나가면 사용자는 같은 메일을 두 번 받는다.
     */
    @Test
    void 이미_인증된_계정에는_환영_메일을_보내지_않는다() {
        User user = User.signUpWithSocial("him@example.com", "한라산");  // 소셜 가입은 이미 인증 상태
        givenUsableTokenFor(user);

        service.verify(RAW_TOKEN);

        verify(welcomeMail, never()).sendAfterCommit(any(), any());
    }

    @Test
    void 못_쓰는_토큰이면_메일도_상태변경도_없다() {
        User user = User.signUpWithEmail("her@example.com", "encoded", "제주러버");
        EmailVerificationToken token = EmailVerificationToken.issue(user, TokenHasher.hash(RAW_TOKEN));
        token.invalidate();
        when(tokenRepository.findByTokenHash(TokenHasher.hash(RAW_TOKEN)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verify(RAW_TOKEN)).isInstanceOf(BaseException.class);

        verify(welcomeMail, never()).sendAfterCommit(any(), any());
    }

    private void givenUsableTokenFor(User user) {
        when(tokenRepository.findByTokenHash(TokenHasher.hash(RAW_TOKEN)))
                .thenReturn(Optional.of(EmailVerificationToken.issue(user, TokenHasher.hash(RAW_TOKEN))));
    }
}
