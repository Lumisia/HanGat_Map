package com.example.hangat.course.model;

import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 스왑 결과 - 화면이 코스 전체를 다시 받지 않고 <b>바뀐 부분만</b> 갱신하도록 준다.
 *
 * <p>{@code updatedItems}에는 교체된 일정과 <b>그 다음 일정</b>이 함께 들어간다 -
 * 장소가 바뀌면 다음 일정의 이동 거리·시간도 같이 변하기 때문이다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CourseSwapResponse(
        Long courseId,
        /** 교체 후 다시 계산한 코스 평균 집중률. 예보 있는 일정이 하나도 없으면 null. */
        BigDecimal averageCongestionRate,
        CongestionLevel congestionLevel,
        String congestionLabel,
        List<SwappedItem> updatedItems,
        /** 화면 토스트 문구. */
        String message,
        CourseResponseDto.BudgetSummaryDto budgetSummary
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SwappedItem(
            Long itemId,
            int dayNo,
            int position,
            LocalDate visitDate,
            Long placeId,
            String placeName,
            String categoryName,
            String imageUrl,
            /** 그날 예보. 커버 밖이면 null - '혼잡 정보 없음'으로 표시한다. */
            Double congestionRate,
            CongestionLevel congestionLevel,
            String congestionLabel,
            String recommendationReason,
            /** 교체 전 장소. 교체된 일정에만 값이 있고, id를 함께 줘야 되돌리기가 가능하다. */
            Long replacedFromPlaceId,
            String replacedFromPlaceName,
            Integer inboundDistanceM,
            Integer inboundTravelMinutes,
            List<CourseResponseDto.CourseItemCostDto> costs
    ) {

        public static SwappedItem of(CourseItem item, Double rate, List<CourseResponseDto.CourseItemCostDto> costs) {
            CongestionLevel level = rate == null
                    ? null : CongestionLevel.from(BigDecimal.valueOf(rate));
            return new SwappedItem(
                    item.getId(),
                    item.getDayNo(),
                    item.getPosition(),
                    item.getVisitDate(),
                    item.getPlace().getId(),
                    item.getPlace().getName(),
                    item.getPlace().getPrimaryCategory().getName(),
                    item.getPlace().getImageUrl(),
                    rate,
                    level,
                    level == null ? null : level.label(),
                    item.getRecommendationReason(),
                    item.getReplacedFromPlace() == null
                            ? null : item.getReplacedFromPlace().getId(),
                    item.getReplacedFromPlace() == null
                            ? null : item.getReplacedFromPlace().getName(),
                    item.getInboundDistanceM(),
                    item.getInboundTravelMinutes() == null
                            ? null : item.getInboundTravelMinutes().intValue(), costs
            );
        }
    }
}
