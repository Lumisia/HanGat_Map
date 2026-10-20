package com.example.hangat.course.share;

import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.CourseResponseDto;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.map.model.enums.BusinessStatus;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 공개 허용 항목만 명시한다. 소유자용 DTO 자체를 직렬화하지 않는다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SharedCourseResponse(String title, LocalDate startDate, LocalDate endDate,
                                   Transport transport, List<Day> days) {
    static SharedCourseResponse from(CourseDetailResponse source) {
        return new SharedCourseResponse(source.title(), source.startDate(), source.endDate(), source.transport(),
                source.days().stream().map(day -> new Day(day.dayNo(), day.visitDate(),
                        day.items().stream().map(item -> Item.from(item, item == day.items().get(0))).toList())).toList());
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Day(int dayNo, LocalDate visitDate, List<Item> items) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(Long id, Long placeId, String placeName, String categoryName, String regionName,
                       BusinessStatus placeBusinessStatus, String imageUrl, Double latitude, Double longitude,
                       int position, LocalTime startTime, LocalTime endTime, Integer inboundDistanceM,
                       Integer inboundTravelMinutes, Double congestionRate, CongestionLevel congestionLevel,
                       String congestionLabel, List<CourseResponseDto.WeatherFactDto> weather) {
        static Item from(CourseDetailResponse.ItemDto item, boolean firstOfDay) {
            return new Item(item.id(), item.placeId(), item.placeName(), item.categoryName(), item.regionName(),
                    item.placeBusinessStatus(), item.imageUrl(), item.latitude(), item.longitude(), item.position(),
                    item.startTime(), item.endTime(), firstOfDay ? null : item.inboundDistanceM(),
                    firstOfDay ? null : item.inboundTravelMinutes(), item.congestionRate(), item.congestionLevel(),
                    item.congestionLabel(), item.weather());
        }
    }
}
