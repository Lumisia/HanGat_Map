package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.config.security.jwt.JwtProvider;
import com.example.hangat.config.security.password.PasswordHasher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.notification.service.inbox.NotificationService;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.auth.RefreshRevokeReason;
import com.example.hangat.user.model.auth.RefreshToken;
import com.example.hangat.user.model.dto.AuthDto;
import com.example.hangat.user.model.dto.AuthInternalDto;
import com.example.hangat.user.model.dto.TokenDto;
import com.example.hangat.user.model.dto.UserDto;
import com.example.hangat.user.repository.RefreshTokenRepository;
import com.example.hangat.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 로그인 / 재발급 / 로그아웃
 * access는 JWT라 무상태로, refresh는 난수로 DB에 저장.
 */
@Service
@RequiredArgsConstructor
public class AuthService {
    @org.springframework.beans.factory.annotation.Value("${app.demo-login.enabled:false}")
    private boolean demoLoginEnabled;

    /** 계정은 운영자가 미리 준비하며 외부 입력으로 로그인 대상을 선택할 수 없다. */
    @Transactional
    public AuthInternalDto.LoginResult loginDemo() {
        if (!demoLoginEnabled) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "데모 체험이 준비 중입니다.");
        User user = userRepository.findByEmail("demo@hangatjeju.com")
                .filter(User::canLogin).filter(User::isEmailVerified)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "데모 체험이 준비 중입니다."));
        return completeLogin(user);
    }

    /**
     * 계정이 없을 때도 BCrypt를 한 번 돌리기 위한 더미 해시
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshRepository;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final NotificationService notifications;
    private final AccountWithdrawalService withdrawal;
    private final jakarta.persistence.EntityManager em;

    // ────────────────────────── 로그인 ──────────────────────────
    @Transactional
    public AuthInternalDto.LoginResult login(
            AuthDto.LoginRequest request) {

        User user = authenticate(request);

        return completeLogin(user);
    }
    /**
     * 이메일 인증 또는 기존 계정 연결을 끝낸 소셜 사용자를 자동 로그인시킨다.
     */
    @Transactional
    public AuthInternalDto.LoginResult loginSocial(
            User user) {

        return completeLogin(user);
    }
    /**
     * 일반 회원의 기존 refresh 세션을 폐기하고 새 토큰을 발급한다.
     * 심사용 공용 계정만 기기별 세션을 보존하며 재사용 탐지 정책은 동일하게 유지한다.
     */
    private AuthInternalDto.LoginResult completeLogin(
            User user) {

        user = lockUser(user.getId());
        if (user.getStatus() == com.example.hangat.user.model.UserStatus.WITHDRAWN) {
            return new AuthInternalDto.LoginResult(null, null, withdrawal.issue(user));
        }
        if (!user.canLogin()) throw new BaseException(user.getStatus().getLoginDeniedStatus());

        if (!user.isDemoAccount()) revokeAll(
                user.getId(),
                RefreshRevokeReason.ROTATED
        );

        user.recordLogin();

        String rawRefresh =
                issueRefreshToken(user);

        TokenDto.LoginResponse body =
                new TokenDto.LoginResponse(
                        UserDto.UserResponse.form(user),
                        accessToken(user)
                );

        notifications.enqueue(
                user.getId(),
                "SECURITY_LOGIN",
                "계정에 로그인했어요",
                "본인이 로그인한 것이 아니라면 비밀번호를 변경해 주세요.",
                "SECURITY",
                null,
                "LOGIN:" + java.util.UUID.randomUUID()
        );

        return new AuthInternalDto.LoginResult(body, rawRefresh);
    }


    /**
     * 비밀번호 확인 -> 그 후에 계정 상태 확인.
     */
    private User authenticate(AuthDto.LoginRequest request) {

        User user = userRepository.findByEmail(EmailNormalizer.normalize(request.email()))
                .orElse(null);
        if (user != null) user = lockUser(user.getId());

        // 계정이 없거나 소셜 전용 계정이어도 더미를 이용해서 소요 시간 맞춤.
        String storedHash = (user != null && user.hasPassword()) ?
                user.getPassword() : DUMMY_HASH;

        boolean matched = passwordHasher.matches(request.password(), storedHash);

        if (user == null || !user.hasPassword() || !matched) {
            throw new BaseException(BaseResponseStatus.PASSWORD_WRONG);
        }
        // 비밀번호가 맞은 사람에게만 구체적인 사유 알려줌.
        if (!user.canLogin() && user.getStatus() != com.example.hangat.user.model.UserStatus.WITHDRAWN) {
            throw new BaseException(user.getStatus().getLoginDeniedStatus());
        }
        return user;
    }

    // ────────────────────────── 재발급 ──────────────────────────

    /**
     * refresh 회전.
     * 폐기된 토큰이 들어오면 탈취로 보고 그 회원 토큰을 전부 없앰.
     *
     * noRollbackFor - BaseException은 RuntimeException이라 기본값이면 롤백됨.
     * 그러면 아래 revokeAll이 같이 되돌아가서 탈취를 잡아놓고도 안 끊김.
     * 이 메서드는 던지기 전에 한 쓰기가 전부 "남아야 하는" 것들이라 예외로 둠.
     */
    @Transactional(noRollbackFor = BaseException.class)
    public AuthInternalDto.ReissueResult reissue(String rawRefreshToken) {
        if(rawRefreshToken == null) {
            throw new BaseException(BaseResponseStatus.JWT_INVALID);
        }

        String hash = TokenHasher.hash(rawRefreshToken);
        RefreshToken candidate = refreshRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.JWT_INVALID));
        User user = lockUser(candidate.getUser().getId());
        RefreshToken token = refreshRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.JWT_INVALID));
        em.refresh(token);

        // 재사용 탐지.
        if(token.isRevoked()) {
            revokeAll(token.getUser().getId(), RefreshRevokeReason.REUSE_DETECTED);
            throw new BaseException(BaseResponseStatus.JWT_INVALID);
        }else if(!token.isUsable()) {
            throw new BaseException(BaseResponseStatus.JWT_EXPIRED);
        }

        if(!user.canLogin()) {
            revokeAll(user.getId(), RefreshRevokeReason.SUSPENDED);
            throw new BaseException(user.getStatus().getLoginDeniedStatus());
        }

        token.touch();
        token.revoke(RefreshRevokeReason.ROTATED);
        String rawRefresh = issueRefreshToken(user, token.getExpiresAt());

        return new AuthInternalDto.ReissueResult(accessToken(user), rawRefresh);
    }

    // ────────────────────────── 로그아웃 ──────────────────────────

    /**
     * 쿠키가 없거나 이미 죽은 토큰이어도 성공으로 끝내기.
     * 로그아웃은 멱등으로 실패로 처리하면 화면이 안 넘어가짐.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if(rawRefreshToken == null) {
            return;
        }
        refreshRepository
                .findByTokenHashForUpdate(
                        TokenHasher.hash(
                                rawRefreshToken
                        )
                )
                .ifPresent(token ->
                        token.revoke(
                                RefreshRevokeReason.LOGOUT
                        )
                );
    }

    // ────────────────────────── 공통 ──────────────────────────

    // 원문은 여기서만 보내고 DB에는 해시만 저장함.
    private String issueRefreshToken(User user) {
        String rawRefresh = TokenHasher.generateToken();
        refreshRepository.save(RefreshToken.issue(user, TokenHasher.hash(rawRefresh)));
        return rawRefresh;
    }

    private String issueRefreshToken(User user, LocalDateTime absoluteExpiresAt) {
        String rawRefresh = TokenHasher.generateToken();
        refreshRepository.save(RefreshToken.rotate(
                user, TokenHasher.hash(rawRefresh), absoluteExpiresAt));
        return rawRefresh;
    }
    private TokenDto.AccessTokenResponse accessToken(User user) {
        return new TokenDto.AccessTokenResponse(
                jwtProvider.createAccessToken(user.getId(), user.getAuthVersion()),
                jwtProvider.getAccessTokenTtlMs()
        );
    }
    private void revokeAll(Long userId, RefreshRevokeReason reason) {
        refreshRepository.findAllActiveForUpdate(userId)
                .forEach(token -> token.revoke(reason));
    }
    private User lockUser(Long id) {
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.JWT_INVALID));
        em.flush();
        em.refresh(user);
        return user;
    }
}
