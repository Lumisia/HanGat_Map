package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.entity.CourseItemCost;
import com.example.hangat.course.model.enums.CostCategory;
import com.example.hangat.map.model.entity.Place;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CourseMenuCostResolverTest {
    @Test
    void priceLookingDescriptionWithoutGoodPriceDesignationCannotBecomeAMealEstimate() {
        var course = Course.builder().people((short) 2).build();
        var place = Place.builder().overview("대표메뉴: 정식 8,000원").isGoodPrice(false).build();
        var item = CourseItem.builder().id(1L).course(course).place(place).build();
        var result = new CourseMenuCostResolver().resolve(course, List.of(item), List.of());
        assertThat(result).singleElement().satisfies(cost -> {
            assertThat(cost.getAccuracyType().name()).isEqualTo("UNKNOWN");
            assertThat(cost.getAmountMax()).isNull();
        });
    }

    @Test
    void existingVerifiedCostsAreNotDoubleCountedOrOverwrittenByMenuEstimates() {
        var course = Course.builder().people((short) 2).build();
        var place = Place.builder().overview("대표메뉴: 정식 8,000원").isGoodPrice(true).build();
        var item = CourseItem.builder().id(1L).course(course).place(place).build();
        var verified = CourseItemCost.verified(course, item, 5L, CostCategory.FOOD, 12000, "검증된 주문");
        var costs = new CourseMenuCostResolver().resolve(course, List.of(item), List.of(verified));
        var result = new CourseBudgetCalculator().calculate(costs);
        assertThat(costs).hasSize(1);
        assertThat(result.summary().verifiedTotal()).isEqualTo(12000);
        assertThat(result.summary().estimatedTotal()).isZero();
        assertThat(result.totalExpectedMax()).isEqualTo(12000);
    }
}
