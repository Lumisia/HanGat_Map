package com.example.hangat.course;

import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.PreferenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CongestionLevelResolverTest {

    @ParameterizedTest
    @CsvSource({
            "0, QUIET",
            "33.3, QUIET",
            "39.9, QUIET",
            "40, NORMAL",
            "69.9, NORMAL",
            "70, CROWDED",
            "99.9, CROWDED",
            "100, CROWDED"
    })
    void resolvesThreeDisplayLevelsAtBoundaries(
            String rate,
            CongestionLevel expected
    ) {
        assertThat(CongestionLevelResolver.resolve(rate)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-number", "-0.01", "100.01"})
    void returnsEmptyWhenRateIsMissingOrInvalid(String rate) {
        assertThat(CongestionLevelResolver.resolve(rate)).isEmpty();
    }

    @Test
    void exposesOnlyTheOfficialThreeUserLabels() {
        assertThat(CongestionLevel.QUIET.label()).isEqualTo("한산");
        assertThat(CongestionLevel.NORMAL.label()).isEqualTo("보통");
        assertThat(CongestionLevel.CROWDED.label()).isEqualTo("혼잡");
    }

    @Test
    void preservesRawRateAfterCalculatingDisplayLevel() throws Exception {
        CongestionDto congestion = congestionWithRate("70");

        assertThat(CongestionLevelResolver.resolve(congestion.getCnctrRate()))
                .contains(CongestionLevel.CROWDED);
        assertThat(congestion.getCnctrRate()).isEqualTo("70");
    }

    @Test
    void doesNotRemoveCrowdedWantCandidate() throws Exception {
        CongestionDto congestion = congestionWithRate("100");
        CourseCandidateDto candidate = new CourseCandidateDto(
                null,
                List.of(congestion),
                PreferenceType.WANT,
                List.of()
        );

        assertThat(CongestionLevelResolver.resolve(congestion.getCnctrRate()))
                .contains(CongestionLevel.CROWDED);
        assertThat(candidate.getPreferenceType()).isEqualTo(PreferenceType.WANT);
        assertThat(candidate.getCongestionData()).containsExactly(congestion);
    }

    private CongestionDto congestionWithRate(String rate) throws Exception {
        CongestionDto congestion = new CongestionDto();
        Field rateField = CongestionDto.class.getDeclaredField("cnctrRate");
        rateField.setAccessible(true);
        rateField.set(congestion, rate);
        return congestion;
    }
}
