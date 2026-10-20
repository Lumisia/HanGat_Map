package com.example.hangat.course;

import java.time.LocalTime;
import java.util.List;

/** Deterministic dwell assumptions used by validation and persistence. */
public final class CourseSchedulePolicy {
    public static final LocalTime DAY_END = LocalTime.of(22, 0);
    private CourseSchedulePolicy() { }

    /** Match only selected styles; multiple matches use the longest dwell, none use category defaults. */
    public static int dwellMinutes(List<String> selectedStyles, List<String> placeStyles, String categoryCode) {
        List<String> selected = selectedStyles == null ? List.of() : selectedStyles;
        List<String> matched = placeStyles == null ? List.of()
                : placeStyles.stream().filter(selected::contains).toList();
        return dwellMinutes(matched, categoryCode);
    }

    public static int dwellMinutes(List<String> styleCodes, String categoryCode) {
        List<String> styles = styleCodes == null ? List.of() : styleCodes;
        if (styles.contains("ACTIVITY") || styles.contains("NATURE")) return 120;
        if (styles.contains("WITH_KIDS") || styles.contains("LOCAL")) return 90;
        if (styles.contains("PHOTO")) return 75;
        if (styles.contains("CAFE") || "CAFE".equals(categoryCode)) return 60;
        return 90;
    }
}
