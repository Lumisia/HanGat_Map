package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.repository.CourseItemCostRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.model.entity.CourseItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 비용 원장 조회, 집계 및 {@code courses} 범위 캐시 갱신의 트랜잭션 경계다. */
@Service
@RequiredArgsConstructor
public class CourseBudgetService {

    private final CourseRepository courseRepository;
    private final CourseItemCostRepository costRepository;
    private final CourseBudgetCalculator calculator;
    private final CourseItemRepository items;

    /** Legacy saved courses get the same estimate without writes from a GET request. */
    @Transactional(readOnly = true)
    public CourseBudgetCalculation calculate(Course course, List<CourseItem> courseItems) {
        return calculator.calculate(new CourseMenuCostResolver().resolve(
                course, courseItems, costRepository.findByCourseId(course.getId())));
    }

    @Transactional
    public void clearItemCosts(Long courseId, Long itemId) {
        costRepository.deleteByCourseAndItem(courseId, itemId);
    }

    @Transactional
    public CourseBudgetCalculation calculateAndCache(Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalStateException(
                        "예산을 계산할 코스를 찾을 수 없습니다: " + courseId));
        var resolved = new CourseMenuCostResolver().resolve(course, items.findItemsWithPlace(courseId),
                costRepository.findByCourseId(courseId));
        costRepository.saveAll(resolved.stream().filter(cost -> cost.getId() == null).toList());
        CourseBudgetCalculation calculation = calculator.calculate(resolved);
        course.updateAggregates(
                calculation.totalExpectedMin(),
                calculation.totalExpectedMax(),
                course.getAverageCongestionRate());
        return calculation;
    }
}
