package com.example.hangat.course.repository;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 코스 조회 - 저장 목록(MY_001)과 메인 샘플(MAIN_002)의 두 얼굴.
 * 논리 삭제 테이블이라 <b>상태 조건 없는 목록 메서드를 만들지 않는다</b> - DELETED가 화면에 새는 사고 방지.
 */
public interface CourseRepository extends JpaRepository<Course, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select course from Course course where course.id = :courseId")
    Optional<Course> findByIdForClaim(@Param("courseId") Long courseId);

    /** 상세 응답에서 선택 숙소를 transaction 안에서 한 번에 복원한다. */
    @Query("""
            select course from Course course
            left join fetch course.accommodationSourceMapping mapping
            left join fetch mapping.source
            left join fetch mapping.place place
            left join fetch place.region
            left join fetch place.primaryCategory
            where course.id = :courseId
            """)
    Optional<Course> findByIdWithAccommodation(@Param("courseId") Long courseId);

    /**
     * 저장 코스 목록(MY_001). 상태를 조건으로 받는 이유: 같은 화면이 DELETED를 제외해야 하고,
     * 논리 삭제라 "userId만으로 전부"를 주는 메서드는 사고 나기 쉽다 - 항상 SAVED를 명시하게 한다.
     */
    Page<Course> findByUserIdAndStatus(Long userId, CourseStatus status, Pageable pageable);

    /**
     * 프리셋별 공개 샘플 코스(MAIN_002). course_preset_publications(20.0)가 보류되면서
     * "가장 마지막에 성공한 배치 결과" = 최신 READY 행으로 대신한다.
     * generation_completed_at이 아니라 id 내림차순인 이유: 같은 배치에서 만든 행들은 완료 시각이
     * 밀리초까지 같을 수 있다 - id는 항상 단조 증가라 결정론적이다.
     */
    Optional<Course> findFirstByPresetIdAndCourseTypeAndStatusOrderByIdDesc(
            Long presetId, CourseType courseType, CourseStatus status);

    /**
     * 프리셋 단위 멱등 장치 - 같은 출발일 READY가 있으면 그 프리셋은 재생성하지 않는다.
     * 배치 부분 실패 후 재실행하면 실패분만 다시 만든다(전체 스킵이 아니라).
     */
    boolean existsByPresetIdAndCourseTypeAndStatusAndStartDate(
            Long presetId, CourseType courseType, CourseStatus status, LocalDate startDate);
}
