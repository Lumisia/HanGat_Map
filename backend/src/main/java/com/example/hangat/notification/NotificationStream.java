package com.example.hangat.notification;

import com.example.hangat.notification.repository.inbox.NotificationOutboxRepository;
import com.example.hangat.notification.repository.inbox.NotificationOutboxRepository.OutboxEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.event.TransactionalEventListener;
import com.example.hangat.notification.model.Notification.InboxChanged;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 현재 접속한 브라우저에 알림함 갱신 신호를 보낸다.
 *
 * 알림 본문은 REST API로 다시 읽는다.
 * 연결이 끊겼거나 중복 신호가 와도 DB 알림함이 기준이다.
 *
 * 현재 운영 API replica 1개를 기준으로 한다.
 */
@Component
@Profile("!batch")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "hangat.notifications.enabled",
        havingValue = "true"
)
public class NotificationStream {

    private static final int MAX_CONNECTIONS_PER_USER = 6;

    private final NotificationOutboxRepository outbox;
    private final com.example.hangat.notification.repository.trip.TripNotificationLockRepository accountLocks;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final Map<Long, Set<SseEmitter>> connections =
            new ConcurrentHashMap<>();

    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(60_000L);

        connections.compute(userId, (id, current) -> {
            Set<SseEmitter> emitters = current == null
                    ? new CopyOnWriteArraySet<>()
                    : current;

            if (emitters.size() >= MAX_CONNECTIONS_PER_USER) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "열린 알림 연결이 너무 많습니다."
                );
            }

            emitters.add(emitter);
            return emitters;
        });

        Runnable cleanup = () -> remove(userId, emitter);

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());

        send(userId, emitter, "invalidate");
        return emitter;
    }

    // ────────────────────────── 전송할 알림 확인 ──────────────────────────

    @Scheduled(fixedDelay = 2000, scheduler = "alarmScheduler")
    public void dispatchOutbox() {
        List<OutboxEntry> rows = outbox.findPending(100);

        for (OutboxEntry row : rows) {
            invalidate(row.userId());

            // 오프라인 사용자도 알림 본문은 DB에 남아 있다.
            // 신호를 보낸 후 프로세스가 종료되면 다음 실행에서 중복 전송될 수 있다.
            outbox.deleteByNotificationId(row.notificationId());
        }
    }

    /**
     * 연결 유지만 수행한다. heartbeat마다 알림 목록을 다시 받지 않는다.
     * 사용자 정보나 알림 본문을 이 신호에 넣지 않는다.
     */
    @Scheduled(fixedDelay = 15000, scheduler = "alarmScheduler")
    public void heartbeat() {
        connections.forEach((userId, emitters) -> emitters.forEach(emitter -> send(userId, emitter, "heartbeat")));
    }

    /** 읽음·삭제가 DB에 커밋된 다음 다른 탭에도 변경을 알린다. */
    @TransactionalEventListener
    public void inboxChanged(InboxChanged event) { invalidate(event.userId()); }

    private void invalidate(Long userId) {
        Set<SseEmitter> emitters = connections.get(userId);
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            send(userId, emitter, "invalidate");
        }
    }

    private void send(Long userId, SseEmitter emitter, String event) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            if (!accountLocks.lockActiveUser(userId)) {
                remove(userId, emitter);
                emitter.complete();
                return;
            }
            sendActive(userId, emitter, event);
        });
    }

    private void sendActive(Long userId, SseEmitter emitter, String event) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event)
                            .data("{}")
            );
        } catch (IOException | IllegalStateException disconnected) {
            remove(userId, emitter);
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        connections.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
