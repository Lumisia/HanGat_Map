package com.example.hangat.domain.congestion;

import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.map.repository.CongestionForecastRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CongestionServiceTest {

    private final CongestionService service = new CongestionService(mock(CongestionForecastRepository.class));

    /**
     * 임계값 경계 - 팀 표준 3단계(한산 <40 / 보통 <70 / 혼잡 >=70), 2026-08-31 통일.
     * 단일 출처는 map의 CongestionLevel.from - 이 테스트는 double 경로(levelOf)가
     * DB 영속 값과 같은 경계를 쓰는지 못 박는다. 프론트 utils/congestion.ts와도 동일해야 한다.
     */
    @ParameterizedTest
    @CsvSource({
            "0,     QUIET",
            "39.9,  QUIET",
            "40,    NORMAL",
            "69.9,  NORMAL",
            "70,    CROWDED",
            "100,   CROWDED",
    })
    void 등급_경계값(double rate, CongestionLevel expected) {
        assertThat(service.levelOf(rate)).isEqualTo(expected);
    }

    @Test
    void 등급_한글_라벨() {
        assertThat(CongestionLevel.QUIET.label()).isEqualTo("한산");
        assertThat(CongestionLevel.NORMAL.label()).isEqualTo("보통");
        assertThat(CongestionLevel.CROWDED.label()).isEqualTo("혼잡");
    }
}
