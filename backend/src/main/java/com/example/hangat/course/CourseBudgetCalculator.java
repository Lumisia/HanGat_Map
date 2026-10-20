package com.example.hangat.course;

import com.example.hangat.course.model.entity.CourseItemCost;
import com.example.hangat.course.model.enums.CostAccuracy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 실제 {@code course_item_costs} fact만 합산하는 재사용 가능한 계산기다. */
@Component
public class CourseBudgetCalculator {

    public CourseBudgetCalculation calculate(
            List<CourseItemCost> costs
    ) {
        List<CourseItemCost> safeCosts = costs == null ? List.of() : costs;
        long verifiedTotal = 0;
        long estimatedMin = 0;
        long estimatedMax = 0;
        int verifiedCount = 0;
        int estimatedCount = 0;
        int unknownCount = 0;
        boolean estimatedMinComplete = true;
        boolean estimatedMaxComplete = true;
        Map<Long, List<CourseBudgetCalculation.CostLine>> itemCosts =
                new LinkedHashMap<>();

        for (CourseItemCost cost : safeCosts) {
            validateCommon(cost);
            CourseBudgetCalculation.CostLine line = toCostLine(cost);
            if (line.courseItemId() != null) {
                itemCosts.computeIfAbsent(line.courseItemId(), ignored -> new ArrayList<>())
                        .add(line);
            }

            if (cost.getAccuracyType() == CostAccuracy.VERIFIED) {
                validateVerified(cost);
                verifiedCount++;
                verifiedTotal += cost.getAmountMax();
            } else if (cost.getAccuracyType() == CostAccuracy.ESTIMATED) {
                validateEstimated(cost);
                estimatedCount++;
                if (cost.getAmountMin() == null) {
                    estimatedMinComplete = false;
                } else {
                    estimatedMin += cost.getAmountMin();
                }
                if (cost.getAmountMax() == null) {
                    estimatedMaxComplete = false;
                } else {
                    estimatedMax += cost.getAmountMax();
                }
            } else {
                validateUnknown(cost);
                unknownCount++;
            }
        }

        boolean hasCostData = verifiedCount > 0 || estimatedCount > 0;
        Integer verified = toInteger(verifiedTotal);
        Integer estimatedMinimum = hasCostData
                ? estimatedCount == 0 ? Integer.valueOf(0)
                : estimatedMinComplete ? toInteger(estimatedMin) : null
                : null;
        Integer estimatedMaximum = hasCostData
                ? estimatedCount == 0 ? Integer.valueOf(0)
                : estimatedMaxComplete ? toInteger(estimatedMax) : null
                : null;
        Integer totalMinimum = sumIfKnown(hasCostData, verified, estimatedMinimum);
        Integer totalMaximum = sumIfKnown(hasCostData, verified, estimatedMaximum);

        return new CourseBudgetCalculation(
                new CourseBudgetCalculation.BudgetSummary(
                        hasCostData,
                        verified,
                        estimatedMaximum,
                        estimatedMinimum,
                        estimatedMaximum,
                        totalMaximum,
                        unknownCount),
                totalMinimum,
                totalMaximum,
                itemCosts);
    }

    private void validateCommon(CourseItemCost cost) {
        if (cost == null || cost.getAccuracyType() == null || cost.getCategory() == null) {
            throw new IllegalStateException("유효하지 않은 코스 비용 fact입니다.");
        }
        if (!"KRW".equals(cost.getCurrency())) {
            throw new IllegalStateException("KRW가 아닌 비용은 코스 예산에 합산할 수 없습니다.");
        }
    }

    private void validateVerified(CourseItemCost cost) {
        if (cost.getAmountMin() == null || cost.getAmountMax() == null
                || !cost.getAmountMin().equals(cost.getAmountMax())) {
            throw new IllegalStateException("VERIFIED 비용은 동일한 최소·최대 금액이 필요합니다.");
        }
    }

    private void validateEstimated(CourseItemCost cost) {
        if (cost.getAmountMin() == null && cost.getAmountMax() == null) {
            throw new IllegalStateException("ESTIMATED 비용에는 최소 또는 최대 금액이 필요합니다.");
        }
        if (cost.getAmountMin() != null && cost.getAmountMax() != null
                && cost.getAmountMin() > cost.getAmountMax()) {
            throw new IllegalStateException("ESTIMATED 비용 범위가 유효하지 않습니다.");
        }
    }

    private void validateUnknown(CourseItemCost cost) {
        if (cost.getAmountMin() != null || cost.getAmountMax() != null) {
            throw new IllegalStateException("UNKNOWN 비용에는 금액을 둘 수 없습니다.");
        }
    }

    private CourseBudgetCalculation.CostLine toCostLine(CourseItemCost cost) {
        Long courseId = cost.getCourse() == null ? null : cost.getCourse().getId();
        Long itemId = cost.getCourseItem() == null ? null : cost.getCourseItem().getId();
        return new CourseBudgetCalculation.CostLine(
                cost.getId(),
                courseId,
                itemId,
                cost.getCategory().name(),
                cost.getAccuracyType().name(),
                cost.getAmountMin(),
                cost.getAmountMax(),
                cost.getCurrency(),
                cost.getBasisText());
    }

    private Integer sumIfKnown(boolean hasCostData, Integer first, Integer second) {
        return !hasCostData || first == null || second == null
                ? null : toInteger((long) first + second);
    }

    private Integer toInteger(long value) {
        return Math.toIntExact(value);
    }
}
