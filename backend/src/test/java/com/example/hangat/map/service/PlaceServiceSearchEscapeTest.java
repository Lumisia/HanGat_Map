package com.example.hangat.map.service;

import com.example.hangat.map.repository.PlaceImageRepository;
import com.example.hangat.map.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 검색어 LIKE 이스케이프 - 사용자가 친 %·_·! 가 와일드카드가 아니라 글자로 DB 에 가야 한다.
 * (실측 2026-09-07: 이스케이프 전에는 "%%" 검색이 아무 장소나 20건을 돌려줬다)
 */
class PlaceServiceSearchEscapeTest {

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final PlaceService service = new PlaceService(places, mock(PlaceImageRepository.class));

    @Test
    void ordinaryQueriesAreUntouched() {
        assertThat(PlaceService.escapeLike("성산일출봉")).isEqualTo("성산일출봉");
        assertThat(PlaceService.escapeLike("Cafe 24 국수")).isEqualTo("Cafe 24 국수");
    }

    @Test
    void wildcardsAndEscapeCharGetEscaped() {
        assertThat(PlaceService.escapeLike("50%할인")).isEqualTo("50!%할인");
        assertThat(PlaceService.escapeLike("a_b")).isEqualTo("a!_b");
        assertThat(PlaceService.escapeLike("%%")).isEqualTo("!%!%");
        assertThat(PlaceService.escapeLike("wow!")).isEqualTo("wow!!");
        assertThat(PlaceService.escapeLike("!%_")).isEqualTo("!!!%!_");
    }

    @Test
    void searchSendsTheEscapedPatternToTheRepository() {
        service.searchPlaces("50%할인", null, null);

        verify(places).searchList(eq("50!%할인"), isNull(), eq(PageRequest.of(0, 30)));
    }

    @Test
    void lengthRuleUsesTheRawQueryNotTheEscapedOne() {
        // "%" 한 글자는 이스케이프하면 두 글자("!%")가 되지만 여전히 2글자 미만으로 거절돼야 한다
        assertThat(service.searchPlaces("%", null, null)).isEmpty();
        verify(places, never()).searchList(anyString(), any(), any());
    }

    @Test
    void categoryScopedSearchIsEscapedToo() {
        service.searchPlaces("a_b", "WEST", List.of("FOOD"));

        verify(places).searchListInCategories(eq("a!_b"), eq("WEST"), eq(List.of("FOOD")), eq(PageRequest.of(0, 30)));
    }
}
