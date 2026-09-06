package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.model.DailyWeather;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 기상청 원자료를 하루 요약으로 접는 규칙 - 한 곳에만 둔다.
 *
 * <p>주간 날씨 조립({@link WeatherService})과 DB 적재({@link WeatherIngestService})가 같은 규칙을 써야
 * 화면과 저장값이 어긋나지 않는다. 여기 복붙해서 고치면 둘이 조용히 갈라진다.
 *
 * <ul>
 *   <li>단기예보: TMN/TMX 우선, 없으면 시간별 TMP의 최소/최대. 강수확률은 하루 최대. 하늘은 정오 값 대표</li>
 *   <li>중기예보: 발표일 기준 며칠째인지로 필드를 고르고, 오전·오후를 하루로 접는다 - 강수확률은 큰 쪽,
 *       하늘은 비·눈이 있는 쪽 우선. 오전만 보면 오후 비를 '맑음'으로 저장하게 된다</li>
 *   <li>값이 없으면 null - 0으로 메우지 않는다</li>
 * </ul>
 */
final class WeatherDailySummarizer {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private WeatherDailySummarizer() {
    }

    /**
     * 하루 요약. {@code ptyCode}는 단기예보 PTY 원코드(중기는 null) - 강수 형태는 코드가 텍스트보다 정확하다.
     */
    record DailySummary(LocalDate date, Integer tempMin, Integer tempMax, String sky,
                        Integer rainProb, String ptyCode) {

        /** 네 값이 다 없으면 그날 자료가 없는 것 - 행을 만들지 않는다. */
        boolean isEmpty() {
            return tempMin == null && tempMax == null && sky == null && rainProb == null;
        }

        DailyWeather toDailyWeather() {
            return new DailyWeather(date, tempMin, tempMax, sky, rainProb);
        }

        PrecipitationType precipitationType() {
            if (ptyCode != null) {
                return PrecipitationType.fromKmaPty(ptyCode);
            }
            return PrecipitationType.fromForecastText(sky);
        }
    }

    /** 단기예보 시간별 행에서 하루 요약. */
    static DailySummary fromShortTerm(LocalDate date, List<ShortTermItem> items) {
        String ymd = date.format(YMD);
        List<ShortTermItem> daily = items.stream()
                .filter(item -> ymd.equals(item.fcstDate()))
                .toList();

        Integer min = categoryValue(daily, "TMN");
        Integer max = categoryValue(daily, "TMX");
        List<Integer> temps = daily.stream()
                .filter(item -> "TMP".equals(item.category()))
                .map(item -> parse(item.fcstValue()))
                .filter(Objects::nonNull)
                .toList();
        if (min == null && !temps.isEmpty()) {
            min = temps.stream().min(Integer::compare).get();
        }
        if (max == null && !temps.isEmpty()) {
            max = temps.stream().max(Integer::compare).get();
        }

        Integer rainProb = daily.stream()
                .filter(item -> "POP".equals(item.category()))
                .map(item -> parse(item.fcstValue()))
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .orElse(null);

        String pty = at(daily, "PTY", "1200");
        return new DailySummary(date, min, max, skyText(daily, pty), rainProb, pty);
    }

    /** 중기예보에서 하루 요약 - 발표일 기준 며칠째인지로 필드를 고른다. 범위 밖은 빈 요약. */
    static DailySummary fromMid(LocalDate date, LocalDate midBaseDate, MidTaItem ta, MidLandItem land) {
        int day = (int) (date.toEpochDay() - midBaseDate.toEpochDay());
        return switch (day) {
            case 4 -> mid(date, ta.taMin4(), ta.taMax4(), land.wf4Am(), land.wf4Pm(), land.rnSt4Am(), land.rnSt4Pm());
            case 5 -> mid(date, ta.taMin5(), ta.taMax5(), land.wf5Am(), land.wf5Pm(), land.rnSt5Am(), land.rnSt5Pm());
            case 6 -> mid(date, ta.taMin6(), ta.taMax6(), land.wf6Am(), land.wf6Pm(), land.rnSt6Am(), land.rnSt6Pm());
            case 7 -> mid(date, ta.taMin7(), ta.taMax7(), land.wf7Am(), land.wf7Pm(), land.rnSt7Am(), land.rnSt7Pm());
            default -> new DailySummary(date, null, null, null, null, null);
        };
    }

    /** 오전·오후를 하루로 접는다 - 강수확률은 큰 쪽, 하늘은 비·눈이 있는 쪽 우선(둘 다 없으면 오전, 오전이 없으면 오후). */
    private static DailySummary mid(LocalDate date, Integer min, Integer max,
                                    String skyAm, String skyPm, Integer popAm, Integer popPm) {
        String sky = isWet(skyAm) ? skyAm : isWet(skyPm) ? skyPm : (skyAm != null ? skyAm : skyPm);
        return new DailySummary(date, min, max, sky, maxNullable(popAm, popPm), null);
    }

    /**
     * 둘 다 없으면 null. 삼항식 한 줄로 쓰면 {@code Math.max}(int) 때문에 전체가 int로 승격돼 null이 언박싱되며
     * NPE가 난다 - 운영 첫 적재에서 실제로 터졌던 자리라 if로 푼다.
     */
    private static Integer maxNullable(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    private static boolean isWet(String text) {
        PrecipitationType type = PrecipitationType.fromForecastText(text);
        return type != null && type.isWet();
    }

    /** 정오 SKY/PTY를 하루 대표값으로 - 강수(PTY)가 있으면 우선. */
    private static String skyText(List<ShortTermItem> daily, String pty) {
        if (pty != null && !"0".equals(pty)) {
            return ("3".equals(pty) || "7".equals(pty)) ? "눈" : "비";
        }
        String sky = at(daily, "SKY", "1200");
        if (sky == null) {
            return null;
        }
        return switch (sky) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> "흐림";
        };
    }

    private static String at(List<ShortTermItem> daily, String category, String time) {
        return daily.stream()
                .filter(item -> category.equals(item.category()))
                .filter(item -> time.equals(item.fcstTime()))
                .map(ShortTermItem::fcstValue)
                .findFirst()
                .orElseGet(() -> daily.stream()
                        .filter(item -> category.equals(item.category()))
                        .map(ShortTermItem::fcstValue)
                        .findFirst()
                        .orElse(null));
    }

    private static Integer categoryValue(List<ShortTermItem> daily, String category) {
        return daily.stream()
                .filter(item -> category.equals(item.category()))
                .map(item -> parse(item.fcstValue()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** "21.0" 같은 소수 문자열도 온다 - 반올림 정수로 통일. */
    private static Integer parse(String value) {
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
