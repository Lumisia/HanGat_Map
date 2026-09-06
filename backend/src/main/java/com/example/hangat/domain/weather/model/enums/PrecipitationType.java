package com.example.hangat.domain.weather.model.enums;

/**
 * 강수 형태 - weather_forecasts.precipitation_type (테이블 명세서 17.0).
 *
 * <p>기상청 단기예보 PTY 코드를 그대로 옮긴 것이다. 코드 목록이 기상청 스펙으로 고정돼 있어
 * MariaDB 네이티브 ENUM 함정(값 추가 시 ALTER)에 걸릴 일이 거의 없다.
 * 모르는 코드는 {@link #UNKNOWN}으로 두고 행을 버리지 않는다 - 기온·강수확률은 여전히 쓸 수 있다.
 */
public enum PrecipitationType {
    NONE,
    RAIN,
    RAIN_SNOW,
    SNOW,
    SHOWER,
    UNKNOWN;

    /** 단기예보 PTY: 0 없음 / 1 비 / 2 비·눈 / 3 눈 / 4 소나기. 그 외(초단기 전용 5~7 포함)는 UNKNOWN. */
    public static PrecipitationType fromKmaPty(String pty) {
        if (pty == null) {
            return null;
        }
        return switch (pty.trim()) {
            case "0" -> NONE;
            case "1" -> RAIN;
            case "2" -> RAIN_SNOW;
            case "3" -> SNOW;
            case "4" -> SHOWER;
            default -> UNKNOWN;
        };
    }

    /**
     * 중기예보 하늘 텍스트("흐리고 비", "구름많고 눈" 등)에서 강수 형태. 중기는 PTY 코드를 주지 않는다.
     * 텍스트가 없으면 null - 모른다고 NONE으로 단정하지 않는다.
     */
    public static PrecipitationType fromForecastText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.contains("소나기")) {
            return SHOWER;
        }
        boolean rain = text.contains("비");
        boolean snow = text.contains("눈");
        if (rain && snow) {
            return RAIN_SNOW;
        }
        if (snow) {
            return SNOW;
        }
        if (rain) {
            return RAIN;
        }
        return NONE;
    }

    /** 비·눈·소나기 어느 쪽이든 강수가 있는 예보인가 - 실내 우선 배치 판단에 쓴다. */
    public boolean isWet() {
        return this == RAIN || this == RAIN_SNOW || this == SNOW || this == SHOWER;
    }
}
