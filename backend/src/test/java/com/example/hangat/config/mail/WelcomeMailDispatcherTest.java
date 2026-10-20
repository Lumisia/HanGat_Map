package com.example.hangat.config.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 환영 메일 발송 시점 - 커밋 전에는 나가면 안 된다.
 * 회수할 수 없는 메일이라 롤백된 가입에 한 번 나가면 되돌릴 방법이 없다.
 */
class WelcomeMailDispatcherTest {

    private final AuthMailSender mailSender = mock(AuthMailSender.class);
    private final WelcomeMailDispatcher dispatcher = new WelcomeMailDispatcher(mailSender);

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 트랜잭션_안에서는_커밋_전까지_보내지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.sendAfterCommit("user@example.com", "제주러버");

        verifyNoInteractions(mailSender);
    }

    @Test
    void 커밋되면_그때_보낸다() {
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.sendAfterCommit("user@example.com", "제주러버");

        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(mailSender).sendWelcome("user@example.com", "제주러버");
    }

    @Test
    void 롤백되면_영영_보내지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.sendAfterCommit("user@example.com", "제주러버");

        // 롤백은 afterCommit 을 부르지 않는다. afterCompletion 만 온다.
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(mailSender, never()).sendWelcome(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /** 트랜잭션 밖에서 불리면 기다릴 커밋이 없다 - 그냥 보낸다. */
    @Test
    void 트랜잭션이_없으면_바로_보낸다() {
        dispatcher.sendAfterCommit("user@example.com", "제주러버");

        verify(mailSender).sendWelcome("user@example.com", "제주러버");
    }
}
