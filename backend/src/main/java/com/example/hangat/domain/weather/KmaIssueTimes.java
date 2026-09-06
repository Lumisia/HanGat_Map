package com.example.hangat.domain.weather;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 기상청 발표분 선택 규칙 - 적재가 "언제 발표된 예보"를 가져올지 정한다.
 *
 * <p><b>단기예보는 최신 발표분이 아니라 05:00 이전 발표분을 쓴다.</b> 06:00 이후 발표분에는 오늘 최저기온(TMN, 06시 값)이
 * 빠지고 15시 이후에는 최고기온(TMX)도 빠져, 요약 폴백이 '남은 시간의 최저·최고'를 오늘 값처럼 만든다.
 * 05:00 → 02:00 → 전날 23:00 순으로 고르면 몇 시에 돌려도 같은 하루 요약이 나온다(재실행 안전).
 *
 * <p>중기예보는 06:00·18:00 두 번뿐이라 최신 발표분을 그대로 쓴다.
 * 두 경우 모두 발표 후 약 10분이 지나야 API에 올라온다.
 */
final class KmaIssueTimes {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter YMDHM = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Duration AVAILABLE_AFTER = Duration.ofMinutes(10);

    private KmaIssueTimes() {
    }

    /** 발표분 하나. API 파라미터 문자열과 DB base_at(UTC) 둘 다 여기서 만든다 - 변환이 한 곳에만 있게. */
    record Issue(LocalDateTime issuedAtKst) {

        String baseDate() {
            return issuedAtKst.format(YMD);
        }

        String baseTime() {
            return issuedAtKst.format(HM);
        }

        String tmFc() {
            return issuedAtKst.format(YMDHM);
        }

        LocalDate issueDate() {
            return issuedAtKst.toLocalDate();
        }

        /** weather_forecasts.base_at - forecast_at과 같은 UTC 기준. */
        LocalDateTime issuedAtUtc() {
            return issuedAtKst.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
    }

    static Issue shortTermFor(LocalDateTime nowKst) {
        LocalDate today = nowKst.toLocalDate();
        if (available(nowKst, today.atTime(5, 0))) {
            return new Issue(today.atTime(5, 0));
        }
        if (available(nowKst, today.atTime(2, 0))) {
            return new Issue(today.atTime(2, 0));
        }
        return new Issue(today.minusDays(1).atTime(23, 0));
    }

    static Issue midFor(LocalDateTime nowKst) {
        LocalDate today = nowKst.toLocalDate();
        if (available(nowKst, today.atTime(18, 0))) {
            return new Issue(today.atTime(18, 0));
        }
        if (available(nowKst, today.atTime(6, 0))) {
            return new Issue(today.atTime(6, 0));
        }
        return new Issue(today.minusDays(1).atTime(18, 0));
    }

    private static boolean available(LocalDateTime nowKst, LocalDateTime issuedAt) {
        return !nowKst.isBefore(issuedAt.plus(AVAILABLE_AFTER));
    }
}
