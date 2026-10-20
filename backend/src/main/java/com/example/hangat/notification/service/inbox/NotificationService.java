package com.example.hangat.notification.service.inbox;

import com.example.hangat.notification.service.trip.TripNotificationService;

import com.example.hangat.notification.model.Notification.NotificationDto;
import com.example.hangat.notification.model.Notification.NotificationPage;
import com.example.hangat.notification.model.Notification.InboxPage;
import com.example.hangat.notification.model.Notification.InboxResult;
import com.example.hangat.notification.model.Notification.InboxChanged;
import com.example.hangat.notification.model.NotificationCategory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.example.hangat.notification.repository.inbox.NotificationCommandRepository;
import com.example.hangat.notification.repository.inbox.NotificationRepository;
import com.example.hangat.notification.repository.inbox.NotificationRepository.NotificationView;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 알림 생성 / 조회 / 읽음 처리.
 *
 * 수신 설정과 여행 확정 상태를 확인한 뒤 알림을 저장한다.
 * SSE 전송 신호도 같은 트랜잭션에서 저장한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationCommandRepository commands;
    private final TripNotificationService tripAlerts;
    private final ApplicationEventPublisher events;
    private final com.example.hangat.notification.repository.trip.TripNotificationLockRepository accountLocks;

    @Value("${hangat.notifications.enabled:false}")
    private boolean enabled;

    // ────────────────────────── 일반 알림 ──────────────────────────

    /**
     * AI 생성 결과 / 보안 / 중요 공지.
     * 기존 AsyncCourseService와 AuthService의 호출 형태는 유지한다.
     */
    @Transactional
    public void enqueue(
            Long userId,
            String type,
            String title,
            String message,
            String targetType,
            String targetId,
            String dedupeKey
    ) {
        if (!enabled || !accountLocks.lockActiveUser(userId) || !tripAlerts.allowsGeneral(userId, type)) {
            return;
        }

        persist(
                userId, type, title, message,
                targetType, targetId, dedupeKey
        );
    }

    // ────────────────────────── 확정 여행 알림 ──────────────────────────

    /**
     * 여행 알림은 반드시 이 메서드로 생성한다.
     *
     * tripVersion에는 외부 조회를 시작할 때 읽은 확정 버전을 전달한다.
     * 발송 직전에 최신 버전을 다시 읽어 덮어쓰면 안 된다.
     */
    @Transactional
    public void enqueueTrip(
            Long userId,
            Long courseId,
            long tripVersion,
            String type,
            String title,
            String message,
            String eventKey
    ) {
        if (!enabled || !accountLocks.lockActiveUser(userId)) {
            return;
        }

        if (eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException(
                    "여행 알림의 eventKey가 필요합니다."
            );
        }

        String dedupeKey = "TRIP:"
                + courseId + ":"
                + tripVersion + ":"
                + type + ":"
                + eventKey;

        if (dedupeKey.length() > 190) {
            throw new IllegalArgumentException(
                    "여행 알림 중복 방지 키는 190자 이하여야 합니다."
            );
        }

        if (!tripAlerts.allowsTrip(
                userId, courseId, tripVersion, type
        )) {
            return;
        }

        persist(
                userId,
                type,
                title,
                message,
                "COURSE",
                courseId.toString(),
                dedupeKey
        );
    }

    /**
     * 알림 본문과 전송 신호를 함께 저장한다.
     * 위의 수신 조건 검사가 끝난 경로에서만 호출한다.
     */
    private void persist(
            Long userId,
            String type,
            String title,
            String message,
            String targetType,
            String targetId,
            String dedupeKey
    ) {
        commands.insertWithOutbox(
                userId, type, title, message,
                targetType, targetId, dedupeKey
        );
    }

    // ────────────────────────── 알림 조회 ──────────────────────────

    public NotificationPage list(
            Long userId,
            Long cursor,
            int requestedSize
    ) {
        int size = Math.max(1, Math.min(50, requestedSize));
        long before = cursor == null ? Long.MAX_VALUE : cursor;

        List<NotificationDto> rows = notifications.findPage(
                userId, before, PageRequest.of(0, size + 1)
        ).stream().map(this::mapNotification).toList();

        boolean hasNext = rows.size() > size;

        List<NotificationDto> items = hasNext
                ? List.copyOf(rows.subList(0, size))
                : List.copyOf(rows);

        String nextCursor = hasNext
                ? items.get(items.size() - 1).id()
                : null;

        return new NotificationPage(
                items,
                nextCursor,
                notifications.countUnread(userId)
        );
    }

    // ────────────────────────── 읽음 처리 ──────────────────────────

    /** 본인 알림을 유형별로 일곱 개씩 조회한다. 과도한 OFFSET 요청은 거부한다. */
    public InboxPage inbox(Long userId, int page, NotificationCategory category) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 번호를 확인하세요.");
        var result = notifications.findInbox(userId, category == NotificationCategory.ALL,
                category == NotificationCategory.OTHER, category.queryTypes(), PageRequest.of(page, 7));
        return new InboxPage(result.getContent().stream().map(this::mapNotification).toList(),
                result.getNumber(), result.getTotalPages(), result.getTotalElements(), notifications.countUnread(userId));
    }

    @Transactional
    public InboxResult read(Long userId, Long notificationId) {
        if (notifications.markRead(userId, notificationId, utcNow()) > 0) events.publishEvent(new InboxChanged(userId));
        return new InboxResult(notifications.countUnread(userId));
    }

    @Transactional
    public InboxResult readAll(Long userId) {
        if (notifications.markAllRead(userId, utcNow()) > 0) events.publishEvent(new InboxChanged(userId));
        return new InboxResult(notifications.countUnread(userId));
    }

    /** 개별 삭제와 전체 삭제 모두 사용자의 알림함에만 영향을 준다. */
    @Transactional
    public InboxResult delete(Long userId, Long notificationId) {
        if (notifications.hide(userId, notificationId, utcNow()) > 0) events.publishEvent(new InboxChanged(userId));
        return new InboxResult(notifications.countUnread(userId));
    }

    @Transactional
    public InboxResult deleteAll(Long userId) {
        if (notifications.hideAll(userId, utcNow()) > 0) events.publishEvent(new InboxChanged(userId));
        return new InboxResult(notifications.countUnread(userId));
    }

    @Transactional
    public InboxResult clearHeader(Long userId) {
        if (notifications.hideHeader(userId, utcNow()) > 0) events.publishEvent(new InboxChanged(userId));
        return new InboxResult(notifications.countUnread(userId));
    }

    /** UTC로 저장한 시각과 숫자 ID를 기존 응답 형식으로 변환한다. */
    private NotificationDto mapNotification(NotificationView row) {
        return new NotificationDto(
                row.getId().toString(),
                row.getType(),
                row.getTitle(),
                row.getMessage(),
                row.getTargetType(),
                row.getTargetId(),
                instant(row.getCreatedAt()),
                instant(row.getReadAt())
        );
    }

    /** DB의 시간대 없는 UTC 시각을 API의 절대 시각으로 바꾼다. */
    private Instant instant(LocalDateTime value) {
        return value == null
                ? null
                : value.toInstant(ZoneOffset.UTC);
    }

    /** DB 컬럼과 동일한 마이크로초 정밀도의 UTC 시각을 반환한다. */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
