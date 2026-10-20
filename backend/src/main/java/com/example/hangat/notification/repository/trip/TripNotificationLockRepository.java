package com.example.hangat.notification.repository.trip;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.notification.model.entity.TripNotificationSettings;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 최초 설정 행 생성 / 코스·설정 잠금 조회.
 * 잠금은 호출한 Service의 트랜잭션이 끝날 때까지 유지한다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TripNotificationLockRepository {

    private final EntityManager entityManager;

    /** 탈퇴·영구 삭제와 같은 회원 잠금을 가장 먼저 잡고 최신 상태를 읽는다. */
    public boolean lockActiveUser(Long userId) {
        var statuses = entityManager.createNativeQuery("SELECT status FROM users WHERE id=:id FOR UPDATE")
                .setParameter("id", userId).getResultList();
        return !statuses.isEmpty() && "ACTIVE".equals(statuses.get(0));
    }

    /** 최초 동시 요청에도 행은 한 개만 만들고, 기존 수신 설정은 덮어쓰지 않는다. */
    public TripNotificationSettings lock(Long userId) {
        if (!lockActiveUser(userId)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "사용할 수 없는 계정입니다.");
        entityManager.flush();
        entityManager.createNativeQuery("""
                INSERT INTO user_notification_settings (
                    user_id, ai_course, forecast_change,
                    congestion, trip_summary, review_request,
                    preferences_version, trip_version, updated_at
                ) VALUES (:userId, TRUE, TRUE, TRUE, TRUE, TRUE, 0, 0, :now)
                ON DUPLICATE KEY UPDATE user_id = user_id
                """)
                .setParameter("userId", userId)
                .setParameter("now", LocalDateTime.now(ZoneOffset.UTC))
                .executeUpdate();

        // 최초 조회도 잠금 조회로 수행해, 이전 스냅샷에 없던 설정 행을 놓치지 않는다.
        TripNotificationSettings settings = entityManager.find(
                TripNotificationSettings.class, userId, LockModeType.PESSIMISTIC_WRITE);
        // 이미 읽은 Entity가 있어도 DB의 최신 값을 잠금 조회로 다시 가져온다.
        entityManager.refresh(settings, LockModeType.PESSIMISTIC_WRITE);
        return settings;
    }

    /** 여행 처리의 잠금 순서는 항상 코스 → 회원 설정이다. */
    public Optional<Course> findSavedCourse(Long userId, Long courseId) {
        if (!lockActiveUser(userId)) return Optional.empty();
        var rows = entityManager.createQuery("""
                select c from Course c
                where c.id = :courseId and c.user.id = :userId and c.status = :status
                """, Course.class)
                .setParameter("courseId", courseId)
                .setParameter("userId", userId)
                .setParameter("status", CourseStatus.SAVED)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        rows.forEach(course -> entityManager.refresh(course, LockModeType.PESSIMISTIC_WRITE));
        return rows.stream().findFirst();
    }
}
