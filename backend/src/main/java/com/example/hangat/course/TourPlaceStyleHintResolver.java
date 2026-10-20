package com.example.hangat.course;

import com.example.hangat.course.model.TourPlaceDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TourPlaceStyleHintResolver {

    private static final String NATURE_CATEGORY = "A01";
    private static final String ACTIVITY_CATEGORY = "A03";
    private static final String CAFE_CATEGORY = "A05020900";

    private TourPlaceStyleHintResolver() {
    }

    static List<String> resolve(TourPlaceDto place) {
        if (place == null) {
            return Collections.emptyList();
        }

        List<String> confirmedStyleHints = new ArrayList<>();

        if (NATURE_CATEGORY.equals(place.getCategory())) {
            confirmedStyleHints.add("NATURE");
        }

        if (ACTIVITY_CATEGORY.equals(place.getCategory())) {
            confirmedStyleHints.add("ACTIVITY");
        }

        if (CAFE_CATEGORY.equals(place.getCategory3())) {
            confirmedStyleHints.add("CAFE");
        }

        for(String style : StoredPlaceStyleResolver.resolve(place.getClassificationCode())) {
            if(!confirmedStyleHints.contains(style))confirmedStyleHints.add(style);
        }
        return List.copyOf(confirmedStyleHints);
    }
}
