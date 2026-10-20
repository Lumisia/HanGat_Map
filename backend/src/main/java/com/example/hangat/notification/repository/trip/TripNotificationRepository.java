package com.example.hangat.notification.repository.trip;

import com.example.hangat.notification.model.entity.TripNotificationSettings;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 여행 확정 / 수신 설정 조회 저장소. */
public interface TripNotificationRepository
        extends JpaRepository<TripNotificationSettings, Long> {

    /**
     * 종료 다음 날 여행까지 포함한다.
     * userId 커서 방식이라 앞 페이지의 변경으로 페이지가 밀리지 않는다.
     */
    List<TripNotificationSettings>
    findByTripCourseIdIsNotNullAndTripEndDateGreaterThanEqualAndUserIdGreaterThanOrderByUserIdAsc(
            LocalDate minimumEndDate,
            Long afterUserId,
            Pageable pageable
    );
}
