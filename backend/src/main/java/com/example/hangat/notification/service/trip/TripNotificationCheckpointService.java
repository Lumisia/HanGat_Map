package com.example.hangat.notification.service.trip;

import com.example.hangat.notification.service.support.TripNotificationJsonService;

import com.example.hangat.notification.model.entity.TripNotificationCheckpoint;
import com.example.hangat.notification.model.entity.TripNotificationSettings;
import com.example.hangat.notification.repository.trip.TripNotificationCheckpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 여행 확정 시 비교 기준값을 만든다.
 * 회원 설정 행의 잠금과 같은 트랜잭션에서 처리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TripNotificationCheckpointService {

    private final TripNotificationCheckpointRepository repository;
    private final TripNotificationFactsService facts;
    private final TripNotificationJsonService json;

    public void reset(
            TripNotificationSettings settings,
            LocalDateTime nowKst
    ) {
        TripNotificationCheckpoint checkpoint =
                repository.lockOrCreate(settings.getUserId());

        initialize(checkpoint, settings, nowKst);
    }

    public TripNotificationCheckpoint load(
            TripNotificationSettings settings,
            LocalDateTime nowKst
    ) {
        TripNotificationCheckpoint checkpoint =
                repository.lockOrCreate(settings.getUserId());

        if (checkpoint.getTripVersion() != settings.getTripVersion()) {
            // 기능 배포 전에 이미 확정한 여행은 첫 조회를 기준값으로 삼는다.
            initialize(checkpoint, settings, nowKst);
        }

        return checkpoint;
    }

    private void initialize(
            TripNotificationCheckpoint checkpoint,
            TripNotificationSettings settings,
            LocalDateTime nowKst
    ) {
        var snapshot = facts.read(
                settings.getTripCourseId(),
                settings.getTripStartDate(),
                settings.getTripEndDate(),
                nowKst
        );

        checkpoint.reset(
                settings.getTripVersion(),
                json.write(snapshot)
        );
    }
}
