package com.example.hangat.notification.service.trip;

import com.example.hangat.notification.repository.trip.TripNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 확정 여행을 작은 페이지로 읽어 회원별 트랜잭션으로 처리한다.
 * 일부 회원 실패가 다른 회원 처리를 막지 않지만, 최종 Job은 실패로 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripNotificationJobService {

    private final TripNotificationRepository repository;
    private final TripNotificationProcessorService processor;

    @Value("${hangat.trip-notifications.enabled:false}")
    private boolean enabled;

    @Value("${hangat.notifications.enabled:false}")
    private boolean notificationsEnabled;

    @Value("${hangat.trip-alerts.enabled:false}")
    private boolean tripSettingsEnabled;

    public void run(String kind) {
        if (!Set.of("weather", "congestion", "reminders").contains(kind)) {
            throw new IllegalArgumentException("지원하지 않는 여행 알림 작업");
        }

        if (!enabled || !notificationsEnabled || !tripSettingsEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        long after = 0;
        int processed = 0;
        int failures = 0;

        while (true) {
            var page = repository
                    .findByTripCourseIdIsNotNullAndTripEndDateGreaterThanEqualAndUserIdGreaterThanOrderByUserIdAsc(
                            now.toLocalDate().minusDays(1),
                            after,
                            PageRequest.of(0, 100)
                    );

            if (page.isEmpty()) {
                break;
            }

            for (var target : page) {
                after = target.getUserId();

                try {
                    processor.process(
                            target.getUserId(),
                            target.getTripCourseId(),
                            target.getTripVersion(),
                            kind,
                            now
                    );
                    processed++;

                } catch (RuntimeException e) {
                    failures++;
                    log.warn("여행 알림 처리 실패 kind={} userId={} error={}",
                            kind,
                            target.getUserId(),
                            e.getClass().getSimpleName());
                }
            }
        }

        log.info("여행 알림 처리 완료 kind={} processed={} failures={}",
                kind, processed, failures);

        if (failures > 0) {
            throw new IllegalStateException(
                    "여행 알림 처리 실패 회원 수: " + failures
            );
        }
    }
}
