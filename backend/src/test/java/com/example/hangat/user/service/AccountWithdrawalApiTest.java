package com.example.hangat.user.service;

import com.example.hangat.user.model.*;
import com.example.hangat.config.security.jwt.JwtProvider;
import com.example.hangat.config.security.password.PasswordHasher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AccountWithdrawalApiTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JwtProvider jwt;
    @Autowired PasswordHasher passwords;
    @Autowired AccountWithdrawalService withdrawal;
    @Autowired OAuthLoginService oauth;
    @Autowired AuthService auth;
    @Autowired com.example.hangat.user.repository.RefreshTokenRepository refresh;
    User user;
    @BeforeEach void setup() {
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS course_generation_jobs (id VARCHAR(40) PRIMARY KEY, user_id BIGINT, status VARCHAR(20), error_code VARCHAR(64), completed_at TIMESTAMP, lease_token VARCHAR(64), lease_until TIMESTAMP)").executeUpdate();
        user = User.signUpWithSocial("withdraw@example.com", "탈퇴테스트");
        user.changePassword(passwords.encodeNew("Correct-password123!"));
        em.persist(user); em.flush();
    }
    @Test void withdrawBlocksExistingAccessAndRequiresAuthenticatedRecovery() throws Exception {
        String access = jwt.createAccessToken(user.getId());
        mvc.perform(post("/users/me/withdrawal").header("Authorization", "Bearer " + access)
                .contentType("application/json").content("{\"email\":\" WITHDRAW@example.com \"}"))
                .andExpect(status().isOk());
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"email\":\"withdraw@example.com\",\"password\":\"Correct-password123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.recoveryRequired").value(true))
                .andExpect(cookie().httpOnly("hangat_recovery", true))
                .andExpect(jsonPath("$.result.accessToken").doesNotExist());
    }
    @Test void mismatchedEmailDoesNotWithdraw() throws Exception {
        mvc.perform(post("/users/me/withdrawal").header("Authorization", "Bearer " + jwt.createAccessToken(user.getId()))
                .contentType("application/json").content("{\"email\":\"other@example.com\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test void cancellationNeverReactivatesOldAccessTokenAndConsumesRecovery() throws Exception {
        String oldAccess = jwt.createAccessToken(user.getId());
        withdrawal.withdraw(user.getId(), user.getEmail());
        String recovery = withdrawal.issue(user);
        var cookie = new jakarta.servlet.http.Cookie("hangat_recovery", recovery);
        mvc.perform(get("/auth/withdrawal").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value("withdraw@example.com"));
        mvc.perform(post("/auth/withdrawal/cancel").cookie(cookie)).andExpect(status().isNoContent());
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        mvc.perform(post("/auth/withdrawal/cancel").cookie(cookie)).andExpect(status().is4xxClientError());
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());
        var login = auth.login(new com.example.hangat.user.model.dto.AuthDto.LoginRequest(
                user.getEmail(), "Correct-password123!"));
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + login.body().tokens().accessToken()))
                .andExpect(status().isOk());
    }

    @Test void wrongPasswordDoesNotIssueRecovery() throws Exception {
        withdrawal.withdraw(user.getId(), user.getEmail());
        mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"email\":\"withdraw@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().is4xxClientError()).andExpect(cookie().doesNotExist("hangat_recovery"));
    }

    @Test void declineKeepsDeadlineAndInvalidatesOnlyThatChallenge() throws Exception {
        withdrawal.withdraw(user.getId(), user.getEmail());
        var deadline = user.getWithdrawnAt();
        String recovery = withdrawal.issue(user);
        mvc.perform(post("/auth/withdrawal/decline")
                .cookie(new jakarta.servlet.http.Cookie("hangat_recovery", recovery)))
                .andExpect(status().isNoContent());
        assertThat(user.getWithdrawnAt()).isEqualTo(deadline);
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        mvc.perform(get("/auth/withdrawal").cookie(new jakarta.servlet.http.Cookie("hangat_recovery", recovery)))
                .andExpect(status().is4xxClientError());
    }

    @Test void linkedSocialAuthenticationOffersRecoveryWithoutSession() {
        em.persist(com.example.hangat.user.model.oauth.UserSocialAccount.link(user,
                com.example.hangat.user.model.oauth.AuthProvider.GOOGLE, "linked-uid", user.getEmail()));
        withdrawal.withdraw(user.getId(), user.getEmail());
        var result = oauth.start(new com.example.hangat.config.security.oauth.OAuthProviderUser(
                com.example.hangat.user.model.oauth.AuthProvider.GOOGLE, "linked-uid", user.getEmail()));
        assertThat(result.loginResult().rawRefreshToken()).isNull();
        assertThat(result.loginResult().body()).isNull();
    }

    @Test void demoAccountCannotWithdraw() throws Exception {
        var demo = User.signUpWithSocial("demo@hangatjeju.com", "데모테스트");
        em.persist(demo); em.flush();
        mvc.perform(post("/users/me/withdrawal").header("Authorization", "Bearer " + jwt.createAccessToken(demo.getId()))
                .contentType("application/json").content("{\"email\":\"demo@hangatjeju.com\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(demo.canLogin()).isTrue();
    }

    @Test void withdrawalRevokesEveryRefreshSessionAndDoesNotRestoreThem() {
        var result = auth.login(new com.example.hangat.user.model.dto.AuthDto.LoginRequest(
                user.getEmail(), "Correct-password123!"));
        withdrawal.withdraw(user.getId(), user.getEmail());
        assertThat(refresh.findAllByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
        withdrawal.cancel(withdrawal.issue(user));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.reissue(result.rawRefreshToken()))
                .isInstanceOf(com.example.hangat.common.exception.BaseException.class);
    }

    @Test void restorationCannotResumeWorkQueuedBeforeWithdrawal() {
        em.createNativeQuery("INSERT INTO course_generation_jobs(id,user_id,status,lease_token) VALUES ('withdraw-job',:id,'RUNNING','lease')")
                .setParameter("id", user.getId()).executeUpdate();
        withdrawal.withdraw(user.getId(), user.getEmail());
        withdrawal.cancel(withdrawal.issue(user));
        var result = (Object[]) em.createNativeQuery("SELECT status, error_code, lease_token FROM course_generation_jobs WHERE id='withdraw-job'").getSingleResult();
        assertThat(result).containsExactly("FAILED", "ACCOUNT_WITHDRAWN", null);
    }

    @Test void staleManagedUserCannotIssueNormalLoginAfterWithdrawal() {
        em.createNativeQuery("UPDATE users SET status='WITHDRAWN', withdrawn_at=CURRENT_TIMESTAMP, auth_version=1 WHERE id=:id")
                .setParameter("id", user.getId()).executeUpdate();
        var result = auth.login(new com.example.hangat.user.model.dto.AuthDto.LoginRequest(
                user.getEmail(), "Correct-password123!"));
        assertThat(result.recoveryRequired()).isTrue();
        assertThat(result.body()).isNull();
        assertThat(result.rawRefreshToken()).isNull();
    }
}
