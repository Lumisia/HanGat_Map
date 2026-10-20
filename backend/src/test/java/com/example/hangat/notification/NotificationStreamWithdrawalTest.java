package com.example.hangat.notification;

import com.example.hangat.notification.repository.inbox.NotificationOutboxRepository;
import com.example.hangat.notification.repository.trip.TripNotificationLockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.Mockito.*;

class NotificationStreamWithdrawalTest {
    @Test void delayedSignalClosesWithdrawnConnectionWithoutSending() throws Exception {
        var locks = mock(TripNotificationLockRepository.class);
        var manager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) {}
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        var stream = new NotificationStream(mock(NotificationOutboxRepository.class), locks, manager);
        var emitter = mock(SseEmitter.class);
        ReflectionTestUtils.invokeMethod(stream, "send", 1L, emitter, "invalidate");
        verify(locks).lockActiveUser(1L);
        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
