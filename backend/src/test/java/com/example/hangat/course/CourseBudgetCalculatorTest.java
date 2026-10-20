package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.entity.CourseItemCost;
import com.example.hangat.course.model.enums.CostCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseBudgetCalculatorTest {

    private final CourseBudgetCalculator calculator = new CourseBudgetCalculator();

    @Test
    void keepsEmptyCostFactsUnknownInsteadOfConfirmingZeroExpectedCost() {
        CourseBudgetCalculation result = calculator.calculate(List.of());

        assertThat(result.summary().hasCostData()).isFalse();
        assertThat(result.summary().verifiedTotal()).isZero();
        assertThat(result.summary().estimatedTotal()).isNull();
        assertThat(result.summary().totalExpected()).isNull();
        assertThat(result.totalExpectedMin()).isNull();
        assertThat(result.totalExpectedMax()).isNull();
        assertThat(result.itemCostsByItemId()).isEmpty();
    }

    @Test
    void separatesVerifiedEstimatedAndUnknownFactsAndGroupsItemCosts() {
        Course course = course(10L);
        CourseItem first = item(101L);
        CourseItem second = item(102L);
        CourseItemCost verified = CourseItemCost.verified(
                course, first, 77L, CostCategory.FOOD, 16000,
                "8,000원 × 2명");
        CourseItemCost estimated = CourseItemCost.estimated(
                course, second, CostCategory.LODGING, 80000, 120000,
                "검증되지 않은 숙박 추정 범위");
        CourseItemCost unknown = CourseItemCost.unknown(
                course, second, CostCategory.TRANSPORT);

        CourseBudgetCalculation result = calculator.calculate(List.of(verified, estimated, unknown));

        assertThat(result.summary().hasCostData()).isTrue();
        assertThat(result.summary().verifiedTotal()).isEqualTo(16000);
        assertThat(result.summary().estimatedMin()).isEqualTo(80000);
        assertThat(result.summary().estimatedMax()).isEqualTo(120000);
        assertThat(result.summary().estimatedTotal()).isEqualTo(120000);
        assertThat(result.totalExpectedMin()).isEqualTo(96000);
        assertThat(result.totalExpectedMax()).isEqualTo(136000);
        assertThat(result.summary().totalExpected()).isEqualTo(136000);
        assertThat(result.summary().unknownCount()).isOne();
        assertThat(result.itemCostsByItemId()).containsOnlyKeys(101L, 102L);
        assertThat(result.itemCostsByItemId().get(101L)).singleElement()
                .satisfies(cost -> {
                    assertThat(cost.accuracyType()).isEqualTo("VERIFIED");
                    assertThat(cost.amountMin()).isEqualTo(16000);
                    assertThat(cost.amountMax()).isEqualTo(16000);
                });
    }

    @Test
    void preservesMissingEstimatedBoundWithoutReplacingItWithZero() {
        CourseItemCost estimated = CourseItemCost.estimated(
                course(10L), null, CostCategory.LODGING, 80000, null, "상한 미확정");

        CourseBudgetCalculation result = calculator.calculate(List.of(estimated));

        assertThat(result.summary().estimatedMin()).isEqualTo(80000);
        assertThat(result.summary().estimatedMax()).isNull();
        assertThat(result.summary().estimatedTotal()).isNull();
        assertThat(result.totalExpectedMin()).isEqualTo(80000);
        assertThat(result.totalExpectedMax()).isNull();
        assertThat(result.summary().totalExpected()).isNull();
    }

    @Test
    void reportsUnknownRowsWithoutTreatingThemAsFreeCostData() {
        CourseBudgetCalculation result = calculator.calculate(List.of(CourseItemCost.unknown(
                        course(10L), null, CostCategory.ACTIVITY)));

        assertThat(result.summary().hasCostData()).isFalse();
        assertThat(result.summary().unknownCount()).isOne();
        assertThat(result.summary().totalExpected()).isNull();
    }

    @Test
    void rejectsInvalidPersistedCostContracts() {
        CourseItemCost invalidVerified = mock(CourseItemCost.class);
        when(invalidVerified.getCategory()).thenReturn(CostCategory.FOOD);
        when(invalidVerified.getAccuracyType())
                .thenReturn(com.example.hangat.course.model.enums.CostAccuracy.VERIFIED);
        when(invalidVerified.getCurrency()).thenReturn("KRW");
        when(invalidVerified.getAmountMin()).thenReturn(10000);
        when(invalidVerified.getAmountMax()).thenReturn(12000);

        assertThatThrownBy(() -> calculator.calculate(List.of(invalidVerified)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFIED");
    }

    private Course course(long id) {
        Course course = mock(Course.class);
        when(course.getId()).thenReturn(id);
        return course;
    }

    private CourseItem item(long id) {
        CourseItem item = mock(CourseItem.class);
        when(item.getId()).thenReturn(id);
        return item;
    }
}
