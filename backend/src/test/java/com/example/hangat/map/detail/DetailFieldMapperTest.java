package com.example.hangat.map.detail;

import com.example.hangat.map.detail.model.PlaceIntroItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * detailIntro2 필드 매핑 검증 - 값은 2026-08-30 실호출에서 그대로 가져왔다.
 *
 * <p>★ 이 매핑은 <b>틀려도 에러가 안 난다.</b> 엉뚱한 필드를 읽으면 그럴듯한 값이 들어가
 * 화면에서만 이상하게 보인다(운영시간 칸에 "무료" 등). 그래서 타입별로 못 박아 둔다.
 */
class DetailFieldMapperTest {

    private final DetailFieldMapper mapper = new DetailFieldMapper();

    /** 필드가 20개라 테스트마다 다 쓰지 않도록 빌더 대신 헬퍼를 둔다. */
    private static PlaceIntroItem item(String typeId, String... kv) {
        String usetime = null, restdate = null, parking = null;
        String usetimeculture = null, restdateculture = null, parkingfee = null, usefee = null;
        String usetimefestival = null, playtime = null;
        String usetimeleports = null, restdateleports = null, parkingleports = null;
        String checkintime = null, checkouttime = null;
        String opentime = null, restdateshopping = null, parkingshopping = null, restroom = null;
        String opentimefood = null, restdatefood = null, parkingfood = null;
        String firstmenu = null, treatmenu = null;
        for (int i = 0; i < kv.length; i += 2) {
            String k = kv[i], v = kv[i + 1];
            switch (k) {
                case "usetime" -> usetime = v;
                case "restdate" -> restdate = v;
                case "parking" -> parking = v;
                case "usetimeculture" -> usetimeculture = v;
                case "restdateculture" -> restdateculture = v;
                case "parkingfee" -> parkingfee = v;
                case "usefee" -> usefee = v;
                case "usetimefestival" -> usetimefestival = v;
                case "playtime" -> playtime = v;
                case "usetimeleports" -> usetimeleports = v;
                case "restdateleports" -> restdateleports = v;
                case "parkingleports" -> parkingleports = v;
                case "checkintime" -> checkintime = v;
                case "checkouttime" -> checkouttime = v;
                case "opentime" -> opentime = v;
                case "restdateshopping" -> restdateshopping = v;
                case "parkingshopping" -> parkingshopping = v;
                case "restroom" -> restroom = v;
                case "opentimefood" -> opentimefood = v;
                case "restdatefood" -> restdatefood = v;
                case "parkingfood" -> parkingfood = v;
                case "firstmenu" -> firstmenu = v;
                case "treatmenu" -> treatmenu = v;
                default -> throw new IllegalArgumentException("모르는 필드: " + k);
            }
        }
        return new PlaceIntroItem("1", typeId, usetime, restdate, parking,
                usetimeculture, restdateculture, parkingfee, usefee,
                usetimefestival, playtime,
                usetimeleports, restdateleports, parkingleports,
                checkintime, checkouttime,
                opentime, restdateshopping, parkingshopping, restroom,
                opentimefood, restdatefood, parkingfood, firstmenu, treatmenu);
    }

    @Test
    void 축제는_usetimefestival이_아니라_playtime을_쓴다() {
        // ★ 실측: 골체오름 벚꽃축제 - usetimefestival 값이 "무료"였다.
        //   이름만 보고 매핑하면 운영시간 칸에 요금이 들어간다
        PlaceIntroItem 축제 = item("15", "usetimefestival", "무료", "playtime", "11:00~18:00");

        assertThat(mapper.operatingHours(축제)).isEqualTo("11:00~18:00");
        assertThat(mapper.operatingHours(축제)).isNotEqualTo("무료");
    }

    @Test
    void 타입마다_다른_운영시간_필드를_읽는다() {
        assertThat(mapper.operatingHours(item("12", "usetime", "09:00~18:00"))).isEqualTo("09:00~18:00");
        assertThat(mapper.operatingHours(item("14", "usetimeculture", "- 09:00~18:00"))).isEqualTo("- 09:00~18:00");
        assertThat(mapper.operatingHours(item("28", "usetimeleports", "09:00~18:30"))).isEqualTo("09:00~18:30");
        assertThat(mapper.operatingHours(item("38", "opentime", "10:00~20:30"))).isEqualTo("10:00~20:30");
        assertThat(mapper.operatingHours(item("39", "opentimefood", "09:00~19:00"))).isEqualTo("09:00~19:00");

        // 숙박은 운영시간 개념이 없어 체크인/아웃으로 대신한다
        assertThat(mapper.operatingHours(item("32", "checkintime", "16:00", "checkouttime", "10:30")))
                .isEqualTo("체크인 16:00 / 체크아웃 10:30");
    }

    @Test
    void 주차는_가능_불가능만_판정하고_요금은_건드리지_않는다() {
        assertThat(mapper.parkingAvailable(item("12", "parking", "불가능"))).isFalse();
        assertThat(mapper.parkingAvailable(item("38", "parkingshopping", "가능"))).isTrue();

        // ★ 문화시설 parkingfee 는 '주차 요금'이다("무료" 등). 가능 여부로 읽으면 안 된다
        assertThat(mapper.parkingAvailable(item("14", "parkingfee", "무료"))).isNull();

        // 애매한 값은 null(모름) - true 로 밀면 없는 정보가 생긴다
        assertThat(mapper.parkingAvailable(item("12", "parking", "주차장 5대 규모"))).isNull();
        assertThat(mapper.parkingAvailable(item("12"))).isNull();
    }

    @Test
    void 입장료는_원문_그대로_두고_숫자로_바꾸지_않는다() {
        String 실측 = "[개인]- 일반 1,500원- 청소년 1,000원-어린이 800원";
        assertThat(mapper.useFee(item("14", "usefee", 실측))).isEqualTo(실측);

        // usefee 는 문화시설에만 있다 - 다른 타입에서 읽으면 안 된다
        assertThat(mapper.useFee(item("12", "usetime", "09:00~18:00"))).isNull();
    }

    @Test
    void 무료_배지는_조건이_붙으면_켜지_않는다() {
        assertThat(mapper.isFree("무료")).isTrue();
        assertThat(mapper.isFree("없음")).isTrue();

        // ★ 실측: 국립제주박물관 - "무료※ 단, 유료 특별전시 제외"
        //   앞부분만 보고 무료로 처리하면 유료 전시를 무료로 안내하게 된다
        assertThat(mapper.isFree("무료※ 단, 유료 특별전시 제외")).isFalse();
        assertThat(mapper.isFree("[개인]- 일반 1,500원")).isFalse();
        assertThat(mapper.isFree(null)).isFalse();
    }

    @Test
    void br_태그를_줄바꿈으로_바꾼다() {
        // KTO는 줄바꿈을 <br>로 준다 - 그대로 두면 화면에 태그가 글자로 보인다
        PlaceIntroItem 음식점 = item("39", "opentimefood", "- 화요일~금요일 09:00~19:00<br>- 토요일 09:00~21:00");

        String hours = mapper.operatingHours(음식점);
        assertThat(hours).doesNotContain("<br>").contains("\n");
    }

    @Test
    void 메뉴는_음식점에서만_읽고_착한가격과_같은_형식으로_만든다() {
        PlaceIntroItem 음식점 = item("39", "firstmenu", "갈치조림", "treatmenu", "갈치구이 / 성게미역국");
        assertThat(mapper.menuText(음식점)).isEqualTo("대표메뉴: 갈치조림 · 갈치구이 · 성게미역국");

        // 음식점(39)이 아니면 읽지 않는다
        assertThat(mapper.menuText(item("12", "usetime", "09:00~18:00"))).isNull();
    }

    @Test
    void 메뉴_중복과_br태그를_정리하고_없으면_null이다() {
        // firstmenu 가 treatmenu 에 또 들어오는 경우가 흔하다 - 중복은 한 번만
        PlaceIntroItem 중복 = item("39", "firstmenu", "흑돼지구이", "treatmenu", "흑돼지구이, 김치찌개<br>된장찌개");
        assertThat(mapper.menuText(중복)).isEqualTo("대표메뉴: 흑돼지구이 · 김치찌개 · 된장찌개");

        assertThat(mapper.menuText(item("39"))).isNull();
    }

    @Test
    void 값이_없으면_빈_문자열이_아니라_null이다() {
        // hours=null 은 '상시 개방'이라는 정보다 - 빈 문자열로 바꾸면 화면이 구분하지 못한다(§1.2)
        assertThat(mapper.operatingHours(item("12", "usetime", "   "))).isNull();
        assertThat(mapper.restDay(item("12"))).isNull();
        assertThat(mapper.toiletAvailable(item("12"))).isNull();
    }
}
