package com.example.hangat.notification.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.notification.NotificationStream;
import com.example.hangat.notification.model.Notification;
import com.example.hangat.notification.model.NotificationCategory;
import com.example.hangat.notification.service.inbox.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 내 알림 조회 / 읽음 처리 / 실시간 연결 API.
 * 모든 경로는 로그인한 본인의 정보만 다룬다.
 */
@RestController
@RequestMapping("/users/me/notifications")
@Profile("!batch")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "hangat.notifications.enabled",
        havingValue = "true"
)
public class NotificationController {

    private final NotificationService notifications;
    private final NotificationStream stream;

    @GetMapping
    public ResponseEntity<BaseResponse<Notification.NotificationPage>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "30") int size
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(BaseResponse.success(
                        notifications.list(userId, cursor, size)
                ));
    }

    @PutMapping("/{id}/read")
    public BaseResponse<Notification.InboxResult> read(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id
    ) {
        return BaseResponse.success(notifications.read(userId, id));
    }

    @PutMapping("/read-all")
    public BaseResponse<Notification.InboxResult> readAll(
            @AuthenticationPrincipal Long userId
    ) {
        return BaseResponse.success(notifications.readAll(userId));
    }

    /** 마이페이지용 번호 기반 조회. 페이지 크기는 서버에서 7개로 고정한다. */
    @GetMapping("/page")
    public ResponseEntity<BaseResponse<Notification.InboxPage>> page(
            @AuthenticationPrincipal Long userId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "ALL") NotificationCategory category) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(BaseResponse.success(notifications.inbox(userId, page, category)));
    }

    @DeleteMapping("/{id}")
    public BaseResponse<Notification.InboxResult> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return BaseResponse.success(notifications.delete(userId, id));
    }

    @DeleteMapping("/header")
    public BaseResponse<Notification.InboxResult> clearHeader(@AuthenticationPrincipal Long userId) {
        return BaseResponse.success(notifications.clearHeader(userId));
    }

    /** 필터·페이지와 관계없이 로그인한 본인의 전체 알림을 삭제한다. */
    @DeleteMapping
    public BaseResponse<Notification.InboxResult> deleteAll(@AuthenticationPrincipal Long userId) {
        return BaseResponse.success(notifications.deleteAll(userId));
    }

    @GetMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<SseEmitter> connect(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(stream.connect(userId));
    }
}
