package com.example.hangat.course.route;

import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import java.util.List;
import java.util.Optional;

/** Explicit, reviewed provider relationships. No name-only identity matching. */
final class TrustedRouteAccessRegistry {
    record Relationship(String originalSource, String originalId, String canonicalName,
            String accessSource, String accessId, String accessName, String roadAddress,
            String type, int maxDistanceMeters, List<String> evidence) {
        boolean matches(KakaoPlace candidate) {
            return candidate != null && accessId.equals(candidate.id())
                    && accessName.equals(candidate.name()) && "PK6".equals(candidate.categoryGroupCode())
                    && roadAddress.equals(candidate.roadAddress());
        }
    }
    private static final List<Relationship> RELATIONSHIPS = List.of(new Relationship(
            "KTO", "127202", "어승생악", "KAKAO_LOCAL", "20588448", "어리목주차장",
            "제주특별자치도 제주시 1100로 2070-61", "SHARED_TRAIL_PARKING", 2000, List.of(
            "https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=bad1e86b-3807-466d-9961-8ae407e7a403",
            "https://www.visitjeju.net/kr/detail/view?contentsid=CONT_000000000500429")));
    static Optional<Relationship> find(String source, String id) {
        return RELATIONSHIPS.stream().filter(r -> r.originalSource().equals(source) && r.originalId().equals(id)).findFirst();
    }
    static String normalizedName(String name) {
        return name == null ? "" : name.replaceAll("\\([^()]*\\)", "").replaceAll("\\s+", "");
    }
    static List<String> mountainQueries(String name, boolean mountain) {
        String base = normalizedName(name);
        if (!mountain) return List.of(base + " 입구", base + " 주차장");
        var locality = java.util.regex.Pattern.compile("\\(([^()]+)\\)").matcher(name);
        if (locality.find() && locality.group(1).trim().matches(".+(동|읍|면)")) {
            String qualified = base + " " + locality.group(1).trim();
            return List.of(qualified + " 입구", qualified + " 주차장", base + " 주차장");
        }
        // Official alias, not suffix invention for arbitrary places.
        if (base.equals("어승생") || base.equals("어승생오름")) base = "어승생악";
        base = base.replaceAll("(탐방안내소|탐방로|주차장|입구)$", "");
        return List.of(base, base + " 탐방로", base + " 입구", base + " 주차장");
    }
}
