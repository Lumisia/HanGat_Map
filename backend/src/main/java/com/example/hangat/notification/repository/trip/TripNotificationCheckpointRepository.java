package com.example.hangat.notification.repository.trip;

import com.example.hangat.notification.model.entity.TripNotificationCheckpoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정 여행 비교 상태 저장소.
 * 호출자가 회원 설정 행을 먼저 잠가야 한다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TripNotificationCheckpointRepository {

    private final EntityManager entityManager;

    public TripNotificationCheckpoint lockOrCreate(Long userId) {
        entityManager.flush();

        TripNotificationCheckpoint row = entityManager.find(
                TripNotificationCheckpoint.class,
                userId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (row == null) {
            row = new TripNotificationCheckpoint(userId);
            entityManager.persist(row);
            return row;
        }

        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        return row;
    }
}
