package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.config.mail.AuthMailSender;
import com.example.hangat.config.mail.WelcomeMailDispatcher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.user.model.auth.EmailVerificationToken;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.dto.AuthDto;
import com.example.hangat.user.repository.EmailVerificationTokenRepository;
import com.example.hangat.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 인증
 * 메일에는 원문 토큰, DB에는 해시만 보냄.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final AuthMailSender mailSender;
    private final WelcomeMailDispatcher welcomeMail;

    // 토큰 발급 + 메일 발송
    @Transactional
    public void issue(User user) {
        // 살아있는 토큰이 있는지 확인.
        tokenRepository.findAllByUserIdAndUsedAtIsNull(user.getId())
                .forEach(EmailVerificationToken::invalidate);

        String rawToken = TokenHasher.generateToken();
        tokenRepository.save(EmailVerificationToken.issue(user,
                TokenHasher.hash(rawToken)));

        mailSender.sendVerification(user.getEmail(), rawToken);
    }

    /**
     *  링크 클릭 처리
     *  토큰 없애고 User의 상태 변경이 한번에 같은 트랜잭션에서 수행되어야 함.
     */
    @Transactional
    public void verify(String rawToken) {
        EmailVerificationToken token = tokenRepository
                .findByTokenHash(TokenHasher.hash(rawToken))
                .orElseThrow(() -> new BaseException(BaseResponseStatus.REQUEST_ERROR));

        if(!token.isUsable()) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }
        token.markUsed();

        User user = token.getUser();
        // verifyEmail() 은 멱등이라 링크를 두 번 눌러도 통과한다.
        // 환영 메일은 멱등하지 않으므로 실제로 처음 인증되는 순간에만 보낸다.
        boolean firstVerification = !user.isEmailVerified();
        user.verifyEmail();
        if (firstVerification) {
            welcomeMail.sendAfterCommit(user.getEmail(), user.getNickname());
        }
    }

    /**
     *  인증 메일 재발송.
     * 계정이 없거나 이미 인증된 경우에도 같은 응답을 반환한다.
     * 응답 차이로 계정 존재 여부가 노출되지 않도록 하기 위함이다.
     */
    @Transactional
    public void resend(AuthDto.ResendVerificationRequest request) {
        userRepository.findByEmail(EmailNormalizer.normalize(request.email()))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::issue);
    }
}
