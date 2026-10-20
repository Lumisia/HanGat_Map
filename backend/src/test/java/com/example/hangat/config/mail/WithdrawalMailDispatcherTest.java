package com.example.hangat.config.mail;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WithdrawalMailDispatcherTest {
    @Test void schedulesOnlyAfterCommitAndExecutorFailureCannotUndoWithdrawal() {
        AuthMailSender sender = mock(AuthMailSender.class);
        var dispatcher = new WithdrawalMailDispatcher(sender);
        var deadline = Instant.parse("2026-10-19T00:00:00Z");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            dispatcher.sendAfterCommit("person@example.com", deadline);
            verifyNoInteractions(sender);
            doThrow(new IllegalStateException("executor unavailable")).when(sender)
                    .sendWithdrawal("person@example.com", deadline);
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertThatCode(() -> callbacks.forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
            verify(sender).sendWithdrawal("person@example.com", deadline);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
    @Test void rollbackDoesNotSendMail() {
        AuthMailSender sender = mock(AuthMailSender.class);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            new WithdrawalMailDispatcher(sender).sendAfterCommit("person@example.com", Instant.now());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(sender);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
