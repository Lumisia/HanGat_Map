package com.example.hangat.notification.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.notification.model.TripNotificationModels;
import com.example.hangat.notification.model.TripNotificationModels.Preferences;
import com.example.hangat.notification.model.TripNotificationModels.Trip;
import com.example.hangat.notification.service.trip.TripNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 내 여행 확정 / 확정 취소 / 알림 수신 설정 API.
 * 로그인한 본인의 정보만 조회하거나 변경한다.
 */
@RestController
@RequestMapping("/users/me")
@Profile("!batch")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "hangat.trip-alerts.enabled",
        havingValue = "true"
)
public class TripNotificationController {

    private final TripNotificationService service;

    // ────────────────────────── 수신 설정 ──────────────────────────

    @GetMapping("/notification-preferences")
    public ResponseEntity<BaseResponse<Preferences>> preferences(
            @AuthenticationPrincipal Long userId
    ) {
        return response(
                service.getPreferences(requireUser(userId))
        );
    }

    @PutMapping("/notification-preferences")
    public ResponseEntity<BaseResponse<Preferences>> savePreferences(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody Preferences request
    ) {
        return response(
                service.savePreferences(requireUser(userId), request)
        );
    }

    // ────────────────────────── 여행 확정 ──────────────────────────

    @GetMapping("/trip-confirmation")
    public ResponseEntity<BaseResponse<Trip>> trip(
            @AuthenticationPrincipal Long userId
    ) {
        return response(
                service.getTrip(requireUser(userId))
        );
    }

    @PutMapping("/trip-confirmation")
    public ResponseEntity<BaseResponse<Trip>> confirm(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody TripNotificationModels.ConfirmRequest request
    ) {
        return response(
                service.confirm(requireUser(userId), request)
        );
    }

    // ────────────────────────── 여행 확정 취소 ──────────────────────────

    @DeleteMapping("/trip-confirmation")
    public ResponseEntity<BaseResponse<Trip>> cancel(
            @AuthenticationPrincipal Long userId,
            @RequestParam("version") long version
    ) {
        return response(
                service.cancel(requireUser(userId), version)
        );
    }

    // ────────────────────────── 공통 ──────────────────────────

    private Long requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        return userId;
    }

    private <T> ResponseEntity<BaseResponse<T>> response(T result) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(BaseResponse.success(result));
    }
}
