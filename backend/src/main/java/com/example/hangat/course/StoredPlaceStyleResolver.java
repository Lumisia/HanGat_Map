package com.example.hangat.course;

import java.util.List;
import java.util.Map;

/** Recommendation preferences derived from KTO taxonomy, not safety or accessibility guarantees. */
final class StoredPlaceStyleResolver {
    private static final Map<String, List<String>> PREFIXES = Map.of(
            "NATURE", List.of("NA"),
            "LOCAL", List.of("EX010100", "EX030100", "EX060300", "HS010600", "VE040100", "VE040200"),
            "CAFE", List.of("FD050100", "FD050200"),
            "ACTIVITY", List.of("LS"),
            "WITH_KIDS", List.of("VE030300", "VE020300", "VE020400", "VE070500", "EX030200", "EX030300"),
            "PHOTO", List.of("NA010300", "NA020800", "NA030300", "NA040700", "VE010200", "VE010800"));

    private StoredPlaceStyleResolver() {}

    static List<String> prefixes(String style) { return PREFIXES.getOrDefault(style, List.of()); }

    static List<String> resolve(String tag) {
        if (tag == null) return List.of();
        return PREFIXES.keySet().stream().sorted()
                .filter(style -> style.equals(tag) || prefixes(style).stream().anyMatch(tag::startsWith)).toList();
    }
}
