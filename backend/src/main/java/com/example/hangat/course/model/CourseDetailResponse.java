package com.example.hangat.course.model;

import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 저장된 코스 한 건의 상세 - 메인 추천 카드·저장 코스 목록에서 코스를 열 때의 응답.
 *
 * <p><b>생성 응답({@code CourseResponseDto})과 다른 DTO인 이유</b>: 그쪽은 생성 시점 산출물
 * (후보 근거·프롬프트 팩트·예산 계산 내역)을 함께 나르는 무거운 계약이라 저장된 행만으로는
 * 채울 수 없다. 여기는 <b>화면이 그리는 데 필요한 것만</b> 담는다.
 *
 * <p><b>혼잡은 항상 두 값을 함께 준다</b>(명세서 21.0/25.0): {@code congestion*}은 지금 예보,
 * {@code planned*}는 저장 시점 스냅숏이다. 예보는 매일 갱신되므로 재열람 시 "그때는 이랬는데
 * 지금은 이렇다"를 화면이 함께 보여줄 수 있어야 한다. 코스 평균도 같은 규칙이라
 * 헤더 배지(지금 기준)와 일정별 값이 어긋나지 않는다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CourseDetailResponse(
        Long id,
        CourseType courseType,
        CourseStatus status,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        int durationDays,
        String durationText,
        Short people,
        Integer budgetTotal,
        Transport transport,
        Integer estimatedCostMin,
        Integer estimatedCostMax,
        /** 지금 예보로 다시 계산한 평균 - 아래 일정들의 congestion_rate와 같은 기준이다. */
        BigDecimal averageCongestionRate,
        CongestionLevel congestionLevel,
        String congestionLabel,
        /** 생성·마지막 변경 시점에 저장해 둔 평균. 지금 값과 다르면 예보가 그만큼 움직인 것이다. */
        BigDecimal plannedAverageCongestionRate,
        /**
         * 일정을 대안으로 교체할 수 있는지 - 소유자 없는 임시 코스이거나 내 저장 코스일 때.
         * 샘플 코스는 공유 자산이라 false다.
         */
        boolean swappable,
        /**
         * 이름 변경·삭제를 할 수 있는지 - <b>내 저장 코스일 때만</b> true.
         * 저장하지 않은 임시 코스는 지울 것도 이름 붙일 것도 없어 swappable과 값이 다르다.
         */
        boolean manageable,
        AccommodationDto accommodation,
        List<DayDto> days
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DayDto(
            int dayNo,
            LocalDate visitDate,
            List<ItemDto> items
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ItemDto(
            Long id,
            Long placeId,
            String placeName,
            String categoryName,
            String regionName,
            String imageUrl,
            Double latitude,
            Double longitude,
            int dayNo,
            int position,
            LocalDate visitDate,
            LocalTime startTime,
            LocalTime endTime,
            CourseItemSource itemSource,
            Integer inboundDistanceM,
            Integer inboundTravelMinutes,
            /** 지금 예보. 커버 밖이면 null - '혼잡 정보 없음'으로 표시한다. */
            Double congestionRate,
            CongestionLevel congestionLevel,
            String congestionLabel,
            /** 저장(생성) 시점 스냅숏. 등급까지 같이 줘서 화면이 임계값을 다시 구현하지 않게 한다. */
            Double plannedCongestionRate,
            CongestionLevel plannedCongestionLevel,
            String plannedCongestionLabel,
            String recommendationReasonCode,
            String recommendationReason,
            /** 스왑으로 바뀐 일정에만 값이 있다. id를 함께 줘야 교체 전 장소로 되짚을 수 있다. */
            Long replacedFromPlaceId,
            String replacedFromPlaceName
    ) {
    }
}
