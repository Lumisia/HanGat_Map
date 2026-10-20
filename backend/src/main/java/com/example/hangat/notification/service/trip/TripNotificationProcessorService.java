package com.example.hangat.notification.service.trip;

import com.example.hangat.notification.service.support.TripNotificationJsonService;
import com.example.hangat.notification.service.inbox.NotificationService;

import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.notification.model.TripNotificationSample;
import com.example.hangat.notification.model.entity.TripNotificationSettings;
import com.example.hangat.notification.repository.trip.TripNotificationLockRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * 한 회원의 확정 여행을 한 트랜잭션에서 처리한다.
 * 잠금 순서는 코스 → 회원 설정 → 비교 상태다.
 */
@Service
@RequiredArgsConstructor
public class TripNotificationProcessorService {

    private static final TypeReference<Map<String, TripNotificationSample>>
            SAMPLE_TYPE = new TypeReference<>() {};

    private final TripNotificationLockRepository locks;
    private final TripNotificationCheckpointService checkpoints;
    private final TripNotificationFactsService facts;
    private final TripNotificationJsonService json;
    private final NotificationService notifications;
    private final CourseItemRepository items;

    @Transactional
    public void process(
            Long userId,
            Long courseId,
            long expectedVersion,
            String kind,
            LocalDateTime nowKst
    ) {
        var course = locks.findSavedCourse(userId, courseId).orElse(null);

        if (course == null) {
            return;
        }

        TripNotificationSettings settings = locks.lock(userId);

        if (!Objects.equals(settings.getTripCourseId(), courseId)
                || settings.getTripVersion() != expectedVersion
                || !Objects.equals(
                settings.getTripStartDate(), course.getStartDate())
                || !Objects.equals(
                settings.getTripEndDate(), course.getEndDate())) {
            return;
        }

        if ("reminders".equals(kind)) {
            remind(settings, nowKst);
            return;
        }

        String sampleKind = switch (kind) {
            case "weather" -> "WEATHER";
            case "congestion" -> "CONGESTION";
            default -> throw new IllegalArgumentException("지원하지 않는 알림 작업");
        };

        var checkpoint = checkpoints.load(settings, nowKst);

        Map<String, TripNotificationSample> last = new TreeMap<>(
                json.read(checkpoint.getLastJson(), SAMPLE_TYPE)
        );

        var current = facts.read(
                courseId,
                settings.getTripStartDate(),
                settings.getTripEndDate(),
                nowKst
        );

        boolean changed = false;
        Set<String> messages = new TreeSet<>();

        for (var entry : current.entrySet()) {
            TripNotificationSample next = entry.getValue();

            if (!sampleKind.equals(next.kind())) {
                continue;
            }

            TripNotificationSample previous = last.get(entry.getKey());

            // 늦게 끝난 이전 발표분은 비교 상태를 되돌리지 않는다.
            if (previous != null
                    && next.issuedAt().isBefore(previous.issuedAt())) {
                continue;
            }

            last.put(entry.getKey(), next);

            // 처음 관측한 날짜·시간은 기준을 세우기만 한다.
            if (previous == null || Objects.equals(previous.value(), next.value())) {
                continue;
            }

            changed = true;

            if ("WEATHER".equals(sampleKind)) {
                messages.add(
                        next.date() + " " + shorten(next.label(), 65)
                                + ": " + label(previous.value())
                                + " → " + label(next.value())
                );

            } else if ("CROWDED".equals(next.value())
                    && !"CROWDED".equals(previous.value())) {
                messages.add(
                        next.date() + " " + shorten(next.label(), 65)
                                + ": 이 관광지 기준 혼잡 단계로 변경"
                );
            }
        }

        checkpoint.advance(json.write(last), changed);

        if (messages.isEmpty()) {
            return;
        }

        String type = "WEATHER".equals(sampleKind)
                ? "FORECAST_CHANGE"
                : "CONGESTION_WORSENED";

        String title = "WEATHER".equals(sampleKind)
                ? "확정 여행의 비·눈 예보가 변경됐어요"
                : "방문 예정 장소의 혼잡 예보가 악화됐어요";

        String body = String.join(
                "\n",
                messages.stream().limit(4).toList()
        );

        if (messages.size() > 4) {
            body += "\n외 " + (messages.size() - 4) + "건";
        }

        body += "WEATHER".equals(sampleKind)
                ? "\n기상청 예보 기준이며 실제 날씨와 다를 수 있어요."
                : "\n한국관광공사 예측 자료이며 실제 혼잡과 다를 수 있어요.";

        // 비교 상태 변경과 알림 저장이 함께 커밋된다.
        notifications.enqueueTrip(
                userId,
                courseId,
                expectedVersion,
                type,
                title,
                body,
                "CHANGE:" + checkpoint.getChangeRevision()
        );
    }

    /** 한국 시각 기준으로 당일 미발송 대상만 생성한다. */
    private void remind(
            TripNotificationSettings settings,
            LocalDateTime now
    ) {
        LocalDate today = now.toLocalDate();
        LocalDate start = settings.getTripStartDate();
        LocalDate end = settings.getTripEndDate();

        if (today.equals(start.minusDays(1)) && now.getHour() >= 18) {
            sendSummary(settings, start, true);
        }

        if (!today.isBefore(start)
                && !today.isAfter(end)
                && now.getHour() >= 7) {
            sendSummary(settings, today, false);
        }

        if (today.equals(end.plusDays(1)) && now.getHour() >= 10) {
            notifications.enqueueTrip(
                    settings.getUserId(),
                    settings.getTripCourseId(),
                    settings.getTripVersion(),
                    "REVIEW_REQUEST",
                    "이번 제주 여행은 어떠셨나요?",
                    "코스에서 방문한 장소를 선택해 리뷰를 남겨 주세요.",
                    "REVIEW:" + end
            );
        }
    }

    private void sendSummary(
            TripNotificationSettings settings,
            LocalDate date,
            boolean beforeDeparture
    ) {
        List<String> names = items.findItemsWithPlace(
                        settings.getTripCourseId()
                ).stream()
                .filter(item -> date.equals(item.getVisitDate()))
                .map(item -> shorten(item.getPlace().getName(), 45))
                .limit(5)
                .toList();

        String body = date + " 일정\n"
                + (names.isEmpty()
                ? "코스에서 방문 일정을 확인해 주세요."
                : String.join(" → ", names));

        notifications.enqueueTrip(
                settings.getUserId(),
                settings.getTripCourseId(),
                settings.getTripVersion(),
                "TRIP_SUMMARY",
                beforeDeparture
                        ? "내일 제주 여행이 시작돼요"
                        : "오늘의 여행 일정을 확인해 보세요",
                body,
                "SUMMARY:" + date + ":" + (beforeDeparture ? "PRE" : "DAY")
        );
    }

    private String label(String value) {
        return switch (value) {
            case "NONE" -> "비·눈 없음";
            case "RAIN", "SHOWER" -> "비";
            case "SNOW" -> "눈";
            case "RAIN_SNOW" -> "비·눈";
            case "QUIET" -> "한산";
            case "NORMAL" -> "보통";
            case "CROWDED" -> "혼잡";
            default -> "정보 없음";
        };
    }

    private String shorten(String value, int maximum) {
        if (value == null) {
            return "";
        }

        return value.length() <= maximum
                ? value
                : value.substring(0, maximum) + "…";
    }
}
