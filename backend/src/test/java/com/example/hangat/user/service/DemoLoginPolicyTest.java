package com.example.hangat.user.service;

import com.example.hangat.user.repository.UserRepository;
import com.example.hangat.config.security.ratelimit.AuthRequestLimiter;
import com.example.hangat.common.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoLoginPolicyTest {
    @Test void disabledByDefaultWithoutQueryingAccounts() {
        var users = mock(UserRepository.class);
        var service = new AuthService(users, null, null, null, null, null, null);
        assertThatThrownBy(service::loginDemo).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(users);
    }
    @Test void limitsArePerIpNotSharedAcrossJudges() {
        var limiter = new AuthRequestLimiter();
        for (int i=0;i<10;i++) limiter.checkDemoLogin("192.0.2.1");
        assertThatThrownBy(() -> limiter.checkDemoLogin("192.0.2.1")).isInstanceOf(BaseException.class);
        assertThatCode(() -> limiter.checkDemoLogin("192.0.2.2")).doesNotThrowAnyException();
    }
}
