package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItemCost;
import com.example.hangat.course.model.enums.CostCategory;
import com.example.hangat.course.repository.CourseItemCostRepository;
import com.example.hangat.course.repository.CourseRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseBudgetServiceTest {

    @Test
    void recalculatesFromTheLedgerAndUpdatesOnlyCostCacheValues() {
        CourseRepository courseRepository = mock(CourseRepository.class);
        CourseItemCostRepository costRepository = mock(CourseItemCostRepository.class);
        Course course = mock(Course.class);
        when(course.getId()).thenReturn(10L);
        when(course.getAverageCongestionRate()).thenReturn(new BigDecimal("42.50"));
        when(courseRepository.findById(10L)).thenReturn(Optional.of(course));
        when(costRepository.findByCourseId(10L)).thenReturn(List.of(
                CourseItemCost.estimated(
                        course, null, CostCategory.LODGING,
                        70000, 120000, "추정 범위")));
        CourseBudgetService service = new CourseBudgetService(
                courseRepository, costRepository, new CourseBudgetCalculator(),
                mock(com.example.hangat.course.repository.CourseItemRepository.class));

        CourseBudgetCalculation result = service.calculateAndCache(10L);

        verify(course).updateAggregates(
                70000, 120000, new BigDecimal("42.50"));
    }
}
