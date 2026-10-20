package com.example.hangat.course.repository;

import com.example.hangat.course.model.entity.CourseItemCost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 비용 원장 조회·재계산 초기화. courses의 min/max는 캐시일 뿐 - 진실은 항상 이 테이블이다. */
public interface CourseItemCostRepository extends JpaRepository<CourseItemCost, Long> {

    /** 비용 내역 화면·합산 재계산용 한 판. */
    List<CourseItemCost> findByCourseId(Long courseId);

    @Modifying
    @Query("delete from CourseItemCost c where c.course.id = :courseId and c.courseItem.id = :itemId")
    int deleteByCourseAndItem(@Param("courseId") Long courseId, @Param("itemId") Long itemId);

    /**
     * 재계산 전 초기화 - 이 테이블은 수정하지 않고 지우고 다시 만든다(엔티티 클래스 주석 참고).
     * 벌크 DELETE인 이유는 {@code CongestionForecastRepository#deleteVersion} 참고.
     */
    @Modifying
    @Query("delete from CourseItemCost c where c.course.id = :courseId")
    int deleteByCourse(@Param("courseId") Long courseId);
}
