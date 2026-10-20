package com.example.hangat.course;

import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.TourPlaceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CourseCandidateStyleHintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"FD050200,CAFE","NA010200,NATURE","EX010100,LOCAL","LS010300,ACTIVITY","VE030300,WITH_KIDS","VE010200,PHOTO"})
    void resolvesCurrentTourismTaxonomy(String tag,String style)throws Exception {
        var place=objectMapper.readValue("{\"contentid\":\"1\",\"lclsSystm3\":\""+tag+"\"}",TourPlaceDto.class);
        assertThat(TourPlaceStyleHintResolver.resolve(place)).contains(style);
    }

    @ParameterizedTest
    @CsvSource({
            "A01,,NATURE",
            "A03,,ACTIVITY",
            "A05,A05020900,CAFE"
    })
    void resolvesOnlyConfirmedTourCategoryStyleHints(
            String category,
            String category3,
            String expectedStyle
    ) throws Exception {
        assertThat(TourPlaceStyleHintResolver.resolve(place(category, category3)))
                .containsExactly(expectedStyle);
    }

    @Test
    void keepsUnknownStylesUnconfirmedInsteadOfInventingHints() throws Exception {
        assertThat(TourPlaceStyleHintResolver.resolve(place("A02", "A02010100")))
                .doesNotContain("LOCAL", "WITH_KIDS", "PHOTO")
                .isEmpty();
    }

    @Test
    void preservesMultipleConfirmedHintsAndDoesNotFilterCandidate() throws Exception {
        TourPlaceDto place = place("A01", "A05020900");
        List<String> hints = TourPlaceStyleHintResolver.resolve(place);
        CourseCandidateDto generalCandidate = new CourseCandidateDto(
                place,
                Collections.emptyList(),
                null,
                hints
        );
        CourseCandidateDto wantWithoutHints = new CourseCandidateDto(
                place("A02", "A02010100"),
                Collections.emptyList(),
                PreferenceType.WANT,
                Collections.emptyList()
        );

        assertThat(generalCandidate.getConfirmedStyleHints())
                .containsExactly("NATURE", "CAFE");
        assertThat(wantWithoutHints.getPreferenceType()).isEqualTo(PreferenceType.WANT);
        assertThat(wantWithoutHints.getConfirmedStyleHints()).isEmpty();
    }

    private TourPlaceDto place(String category, String category3) throws Exception {
        return objectMapper.readValue(
                "{\"title\":\"테스트 장소\",\"cat1\":\"" + category
                        + "\",\"cat3\":\"" + category3 + "\"}",
                TourPlaceDto.class
        );
    }
}
