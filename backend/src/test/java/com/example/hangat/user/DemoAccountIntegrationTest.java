package com.example.hangat.user;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.config.security.password.PasswordHasher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.config.security.token.VerificationCodeHasher;
import com.example.hangat.config.security.token.Purpose;
import com.example.hangat.user.model.dto.UserDto;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.auth.PasswordResetRequest;
import com.example.hangat.user.model.dto.AuthDto;
import com.example.hangat.user.repository.PasswordResetRequestRepository;
import com.example.hangat.user.repository.RefreshTokenRepository;
import com.example.hangat.user.repository.UserRepository;
import com.example.hangat.user.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.demo-login.enabled=true")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DemoAccountIntegrationTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    private static final String PASSWORD = "SampleOnly!4826";
    @Autowired AuthService auth;
    @Autowired PasswordHasher hasher;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordResetRequestRepository resets;
    @Autowired PasswordResetCodeService codes;
    @Autowired PasswordResetService passwords;
    @Autowired VerificationCodeHasher codeHasher;

    private User seed(String email) {
        User user = User.signUpWithEmail(email, hasher.encodeNew(PASSWORD, PASSWORD), "한갓지도 데모계정");
        user.verifyEmail();
        return users.saveAndFlush(user);
    }
    private String login(User user) {
        return auth.login(new AuthDto.LoginRequest(user.getEmail(), PASSWORD)).rawRefreshToken();
    }

    @Test void publicDemoLoginOnlyIssuesDemoSession() throws Exception {
        seed("demo@hangatjeju.com");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/demo-login")
                        .contentType("application/json").content("{\"email\":\"ordinary@example.com\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result.user.email").value("demo@hangatjeju.com"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result.tokens.accessToken").isString())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")));
    }
    @Test void missingDemoAccountIsNotAutomaticallyCreated() throws Exception {
        long before = users.count();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/demo-login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
        assertThat(users.count()).isEqualTo(before);
    }

    @Test void passwordlessDemoAccountCanUseButtonButNotPasswordLogin() {
        User user = User.signUpWithSocial("demo@hangatjeju.com", "한갓지도 데모계정");
        users.saveAndFlush(user);
        assertThat(user.getPassword()).isNull();
        assertThat(auth.loginDemo().rawRefreshToken()).isNotBlank();
        assertThatThrownBy(() -> auth.login(new AuthDto.LoginRequest(user.getEmail(), PASSWORD)))
                .isInstanceOf(BaseException.class);
    }

    @Test void demoDevicesKeepIndependentSessions() {
        User user = seed("demo@hangatjeju.com");
        String first = login(user), second = login(user);
        String refreshedFirst = auth.reissue(first).rawRefreshToken();
        String refreshedSecond = auth.reissue(second).rawRefreshToken();
        auth.logout(refreshedFirst);
        assertThat(auth.reissue(refreshedSecond).rawRefreshToken()).isNotBlank();
    }
    @Test void normalAccountStillRevokesPreviousLogin() {
        User user = seed("regular@example.com");
        String first = login(user); login(user);
        assertThat(tokens.findByTokenHashForUpdate(TokenHasher.hash(first)).orElseThrow().isRevoked()).isTrue();
    }
    @Test void demoStillDetectsRefreshReplay() {
        User user = seed("demo@hangatjeju.com");
        String first = login(user), second = login(user);
        auth.reissue(first);
        assertThatThrownBy(() -> auth.reissue(first)).isInstanceOf(BaseException.class);
        assertThat(tokens.findByTokenHashForUpdate(TokenHasher.hash(second)).orElseThrow().isRevoked()).isTrue();
    }
    @Test void demoResetDoesNotIssueRequest() {
        seed("demo@hangatjeju.com");
        long before = resets.count();
        assertThat(codes.sendCode(new AuthDto.SendResetCodeRequest("demo@hangatjeju.com"))).isNotNull();
        assertThat(resets.count()).isEqualTo(before);
    }
    @Test void evenPreviouslyIssuedTicketCannotChangeDemoPassword() {
        User user = seed("demo@hangatjeju.com");
        String oldHash = user.getPassword();
        PasswordResetRequest reset = PasswordResetRequest.issue(user, TokenHasher.generateId(), "0".repeat(64));
        reset.markVerified(TokenHasher.hash("test-ticket"));
        resets.saveAndFlush(reset);
        assertThatThrownBy(() -> passwords.resetPassword(new AuthDto.ResetPasswordRequest("test-ticket", "AnotherOnly!4826", "AnotherOnly!4826")))
                .isInstanceOf(BaseException.class);
        assertThat(user.getPassword()).isEqualTo(oldHash);
    }
    @Test void directPasswordMutationIsAlsoBlocked() {
        User user = seed("demo@hangatjeju.com");
        assertThatThrownBy(() -> user.changePassword("replacement-hash")).isInstanceOf(BaseException.class);
    }
    @Test void previouslyIssuedCodeCannotMintResetTicket() {
        User user = seed("demo@hangatjeju.com");
        String requestId = TokenHasher.generateId();
        PasswordResetRequest reset = PasswordResetRequest.issue(user, requestId,
                codeHasher.hash(Purpose.PASSWORD_RESET, requestId, "234567"));
        resets.saveAndFlush(reset);
        assertThatThrownBy(() -> codes.verifyCode(new AuthDto.VerifyResetCodeRequest("234567", requestId)))
                .isInstanceOf(BaseException.class);
        assertThat(reset.getTicketHash()).isNull();
    }
    @Test void serverReportsDemoPolicyWithoutRelyingOnNickname() {
        User demo = seed("DEMO@HANGATJEJU.COM");
        assertThat(UserDto.UserResponse.form(demo).demoAccount()).isTrue();
        User ordinary = User.signUpWithEmail("ordinary@example.com", "hash", "한갓지도 데모계정");
        assertThat(UserDto.UserResponse.form(ordinary).demoAccount()).isFalse();
    }
    @Test void normalPasswordMutationStillWorks() {
        User user = seed("regular@example.com");
        user.changePassword("replacement-hash");
        assertThat(user.getPassword()).isEqualTo("replacement-hash");
    }
}
