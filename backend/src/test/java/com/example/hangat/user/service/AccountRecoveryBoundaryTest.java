package com.example.hangat.user.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.config.mail.WithdrawalMailDispatcher;
import com.example.hangat.config.security.token.TokenHasher;
import com.example.hangat.user.model.User;
import com.example.hangat.user.model.auth.AccountRecoveryToken;
import com.example.hangat.user.repository.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountRecoveryBoundaryTest {
    final UserRepository users = mock(UserRepository.class);
    final AccountRecoveryTokenRepository recovery = mock(AccountRecoveryTokenRepository.class);
    final User user = User.builder().id(7L).email("person@example.com").build();
    final Instant deletedAt = Instant.parse("2026-10-19T00:00:00Z");

    AccountWithdrawalService service(Instant instant, LocalDateTime expires) {
        user.withdrawAt(LocalDateTime.parse("2026-09-19T00:00:00"));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(recovery.findByTokenHash(TokenHasher.hash("raw"))).thenReturn(Optional.of(
                new AccountRecoveryToken(7L, TokenHasher.hash("raw"), expires)));
        return new AccountWithdrawalService(users, mock(RefreshTokenRepository.class), recovery,
                mock(EntityManager.class), mock(WithdrawalMailDispatcher.class), Clock.fixed(instant, ZoneOffset.UTC));
    }
    @Test void lastInstantBeforeThirtyDaysCanRecover() {
        var service = service(deletedAt.minusNanos(1), LocalDateTime.parse("2026-10-19T00:10:00"));
        assertThat(service.details("raw").deleteAt()).isEqualTo(deletedAt);
        service.cancel("raw");
        assertThat(user.canLogin()).isTrue();
    }
    @Test void exactThirtyDaysCannotRecoverEvenIfCleanupHasNotRun() {
        var service = service(deletedAt, LocalDateTime.parse("2026-10-19T00:10:00"));
        assertThatThrownBy(() -> service.cancel("raw")).isInstanceOf(BaseException.class);
        assertThat(user.canLogin()).isFalse();
    }
    @Test void afterThirtyDaysCannotRecover() {
        var service = service(deletedAt.plusNanos(1), LocalDateTime.parse("2026-10-19T00:10:00"));
        assertThatThrownBy(() -> service.issue(user)).isInstanceOf(BaseException.class);
    }
    @Test void exactTokenExpiryCannotRecover() {
        var service = service(Instant.parse("2026-09-19T00:10:00Z"), LocalDateTime.parse("2026-09-19T00:10:00"));
        assertThatThrownBy(() -> service.cancel("raw")).isInstanceOf(BaseException.class);
    }
}
