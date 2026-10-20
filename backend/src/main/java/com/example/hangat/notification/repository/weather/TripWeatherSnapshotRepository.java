package com.example.hangat.notification.repository.weather;

import com.example.hangat.map.model.entity.Region;
import com.example.hangat.notification.model.entity.TripWeatherSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 알림용 최신 날씨의 JPA 저장소.
 * 최초 저장 경합도 막기 위해 이미 존재하는 권역 행을 먼저 잠근다.
 */
@Repository
@RequiredArgsConstructor
public class TripWeatherSnapshotRepository {

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<TripWeatherSnapshot> findAll() {
        return entityManager.createQuery(
                "select s from TripWeatherSnapshot s",
                TripWeatherSnapshot.class
        ).getResultList();
    }

    @Transactional
    public void save(
            Short regionId,
            LocalDateTime issuedAt,
            LocalDateTime fetchedAt,
            String payloadJson
    ) {
        Region region = entityManager.find(
                Region.class,
                regionId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (region == null) {
            throw new IllegalStateException("날씨 권역을 찾을 수 없습니다.");
        }

        TripWeatherSnapshot row = entityManager.find(
                TripWeatherSnapshot.class,
                regionId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (row == null) {
            entityManager.persist(
                    new TripWeatherSnapshot(
                            regionId, issuedAt, fetchedAt, payloadJson
                    )
            );
            return;
        }

        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        row.replace(issuedAt, fetchedAt, payloadJson);
    }
}
