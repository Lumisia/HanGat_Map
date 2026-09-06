package com.example.hangat.map.detail;

import com.example.hangat.map.detail.model.PlaceIntroItem;
import org.springframework.stereotype.Component;

/**
 * detailIntro2 응답 → 우리 컬럼. 타입마다 필드명이 달라 이 클래스가 그 차이를 흡수한다.
 *
 * <p>매핑표는 2026-08-30 실측으로 확정했다.
 */
@Component
public class DetailFieldMapper {

    /** 운영시간. 값이 없으면 null - 빈 문자열로 두면 화면이 '상시 개방'과 구분하지 못한다. */
    public String operatingHours(PlaceIntroItem it) {
        return switch (nz(it.contenttypeid())) {
            case "12" -> clean(it.usetime());
            case "14" -> clean(it.usetimeculture());
            // ★ 축제는 usetimefestival 이 아니라 playtime 이다.
            //   usetimefestival 은 이름과 달리 이용요금이라, 잘못 쓰면 시간 칸에 "무료"가 들어간다
            case "15" -> clean(it.playtime());
            case "28" -> clean(it.usetimeleports());
            // 숙박은 체크인/아웃이 운영시간 자리를 대신한다
            case "32" -> checkInOut(it);
            case "38" -> clean(it.opentime());
            case "39" -> clean(it.opentimefood());
            default -> null;
        };
    }

    /** 쉬는날. 숙박·축제는 이 개념이 없다. */
    public String restDay(PlaceIntroItem it) {
        return switch (nz(it.contenttypeid())) {
            case "12" -> clean(it.restdate());
            case "14" -> clean(it.restdateculture());
            case "28" -> clean(it.restdateleports());
            case "38" -> clean(it.restdateshopping());
            case "39" -> clean(it.restdatefood());
            default -> null;
        };
    }

    /**
     * 주차 가능 여부.
     *
     * <p>★ 문화시설의 {@code parkingfee}는 <b>주차 요금</b>이지 가능 여부가 아니다("무료" 같은 값).
     * 여기에 넣으면 "무료"를 '주차 가능'으로 오해하게 되므로 제외한다.
     *
     * <p>텍스트도 제각각이라 "가능/불가능"만 판정하고 나머지는 null이다 -
     * 애매한 값을 true로 밀면 없는 정보가 생긴다.
     */
    public Boolean parkingAvailable(PlaceIntroItem it) {
        String raw = switch (nz(it.contenttypeid())) {
            case "12" -> it.parking();
            case "28" -> it.parkingleports();
            case "38" -> it.parkingshopping();
            case "39" -> it.parkingfood();
            default -> null;
        };
        return toAvailable(raw);
    }

    /** 화장실. 실측상 쇼핑(38)에만 있다. */
    public Boolean toiletAvailable(PlaceIntroItem it) {
        return "38".equals(nz(it.contenttypeid())) ? toAvailable(it.restroom()) : null;
    }

    /**
     * 입장료 <b>원문</b>. 숫자로 바꾸지 않는다.
     *
     * <p>"[개인]- 일반 1,500원- 청소년 1,000원..." 처럼 대상별 요금표가 통째로 온다.
     * 대표값 하나를 고르려면 근거가 필요한데 그게 없어서, 파싱하면 없는 가격을 만들어내는 셈이 된다.
     */
    public String useFee(PlaceIntroItem it) {
        return "14".equals(nz(it.contenttypeid())) ? clean(it.usefee()) : null;
    }

    /**
     * 대표 메뉴 문구 (음식점 39 전용). 착한가격 overview 와 같은 "대표메뉴: A · B" 형식 -
     * 화면 메뉴 섹션을 그대로 재사용한다. KTO는 가격을 주지 않아 메뉴명만 담긴다.
     */
    public String menuText(PlaceIntroItem it) {
        if (!"39".equals(nz(it.contenttypeid()))) {
            return null;
        }
        java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>();
        for (String raw : new String[]{clean(it.firstmenu()), clean(it.treatmenu())}) {
            if (raw == null) {
                continue;
            }
            for (String piece : raw.split("[,/\n]")) {
                String t = piece.trim();
                if (!t.isEmpty()) {
                    items.add(t);
                }
            }
        }
        return items.isEmpty() ? null : "대표메뉴: " + String.join(" · ", items);
    }

    /** 입장료 원문이 '무료'만 뜻하는가 - 화면 배지용. 조건이 붙으면(※ 단, ...) 무료로 보지 않는다. */
    public boolean isFree(String useFeeText) {
        if (useFeeText == null) {
            return false;
        }
        String t = useFeeText.replaceAll("\\s", "");
        return t.equals("무료") || t.equals("없음");
    }

    private String checkInOut(PlaceIntroItem it) {
        String in = clean(it.checkintime());
        String out = clean(it.checkouttime());
        if (in == null && out == null) {
            return null;
        }
        return "체크인 " + (in == null ? "-" : in) + " / 체크아웃 " + (out == null ? "-" : out);
    }

    /** "가능"/"불가능"만 판정한다. "무료"·"주차장 있음" 같은 값은 null(모름)로 둔다. */
    private Boolean toAvailable(String raw) {
        String t = clean(raw);
        if (t == null) {
            return null;
        }
        String s = t.replaceAll("\\s", "");
        if (s.startsWith("불가능") || s.startsWith("없음")) {
            return false;
        }
        if (s.startsWith("가능") || s.startsWith("있음")) {
            return true;
        }
        return null;
    }

    /** KTO는 줄바꿈을 {@code <br>} 로 준다. 화면이 그대로 뿌리면 태그가 글자로 보인다. */
    private String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
        return t.isEmpty() ? null : t;
    }

    private String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
