package com.example.hangat.course;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 검증된 비용 원장을 한 번 집계한 불변 결과다.
 *
 * <p>{@code totalExpected}는 보수적으로 예상 범위의 상한을 사용한다.
 * 범위 자체는 {@code totalExpectedMin/Max}로 함께 보존한다. 비용 금액 fact가 없으면
 * 합계는 {@code null}이며, 빈 원장을 0원 코스로 확정하지 않는다.
 */
public record CourseBudgetCalculation(
        BudgetSummary summary,
        Integer totalExpectedMin,
        Integer totalExpectedMax,
        Map<Long, List<CostLine>> itemCostsByItemId
) {

    public CourseBudgetCalculation {
        itemCostsByItemId = immutableMap(itemCostsByItemId);
    }

    public static CourseBudgetCalculation noData() {
        return new CourseBudgetCalculation(
                new BudgetSummary(
                        false, 0, null, null, null, null, 0),
                null,
                null,
                Map.of());
    }

    private static Map<Long, List<CostLine>> immutableMap(
            Map<Long, List<CostLine>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CostLine>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                key, value == null ? List.of() : List.copyOf(value)));
        return Map.copyOf(copy);
    }

    public record BudgetSummary(
            boolean hasCostData,
            int verifiedTotal,
            Integer estimatedTotal,
            Integer estimatedMin,
            Integer estimatedMax,
            Integer totalExpected,
            int unknownCount
    ) {
    }

    public record CostLine(
            Long id,
            Long courseId,
            Long courseItemId,
            String category,
            String accuracyType,
            Integer amountMin,
            Integer amountMax,
            String currency,
            String basisText
    ) {
    }
}
