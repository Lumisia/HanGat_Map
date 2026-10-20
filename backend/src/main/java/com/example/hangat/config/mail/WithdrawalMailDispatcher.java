package com.example.hangat.config.mail;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawalMailDispatcher {
    private final AuthMailSender sender;

    public void sendAfterCommit(String email, Instant deleteAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Withdrawal mail requires an account transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { sender.sendWithdrawal(email, deleteAt); }
                catch (RuntimeException ignored) { log.warn("Withdrawal mail scheduling failed"); }
            }
        });
    }
}
