package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.KmaIssueTimes.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 발표분 선택 규칙 - 몇 시에 돌려도 같은 하루 요약이 나오게 하는 핵심 규칙이라 시각별로 못 박는다. */
class KmaIssueTimesTest {

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute);
    }

    @Test
    @DisplayName("새벽 3시 30분(코스 배치 직전): 단기는 오늘 02시 발표분, 중기는 전날 18시 발표분")
    void beforeDawn() {
        assertThat(KmaIssueTimes.shortTermFor(at(10, 3, 30)).issuedAtKst()).isEqualTo(at(10, 2, 0));
        assertThat(KmaIssueTimes.midFor(at(10, 3, 30)).issuedAtKst()).isEqualTo(at(9, 18, 0));
    }

    @Test
    @DisplayName("06시 30분: 단기 05시, 중기 06시 - 둘 다 오늘 아침 발표분")
    void morning() {
        assertThat(KmaIssueTimes.shortTermFor(at(10, 6, 30)).issuedAtKst()).isEqualTo(at(10, 5, 0));
        assertThat(KmaIssueTimes.midFor(at(10, 6, 30)).issuedAtKst()).isEqualTo(at(10, 6, 0));
    }

    @Test
    @DisplayName("저녁 8시: 단기는 최신(17시)이 아니라 05시 발표분 - 오늘 최저·최고기온이 빠진 발표분을 쓰지 않는다")
    void eveningStillUsesFiveAm() {
        assertThat(KmaIssueTimes.shortTermFor(at(10, 20, 0)).issuedAtKst()).isEqualTo(at(10, 5, 0));
        assertThat(KmaIssueTimes.midFor(at(10, 20, 0)).issuedAtKst()).isEqualTo(at(10, 18, 0));
    }

    @Test
    @DisplayName("새벽 1시: 오늘 발표분이 아직 없어 전날 23시·18시 발표분")
    void afterMidnight() {
        assertThat(KmaIssueTimes.shortTermFor(at(10, 1, 0)).issuedAtKst()).isEqualTo(at(9, 23, 0));
        assertThat(KmaIssueTimes.midFor(at(10, 1, 0)).issuedAtKst()).isEqualTo(at(9, 18, 0));
    }

    @Test
    @DisplayName("발표 후 10분이 지나야 API에 올라온다 - 05:05는 02시 발표분, 05:10부터 05시 발표분")
    void tenMinuteAvailabilityMargin() {
        assertThat(KmaIssueTimes.shortTermFor(at(10, 5, 5)).issuedAtKst()).isEqualTo(at(10, 2, 0));
        assertThat(KmaIssueTimes.shortTermFor(at(10, 5, 10)).issuedAtKst()).isEqualTo(at(10, 5, 0));
        assertThat(KmaIssueTimes.midFor(at(10, 18, 5)).issuedAtKst()).isEqualTo(at(10, 6, 0));
        assertThat(KmaIssueTimes.midFor(at(10, 18, 10)).issuedAtKst()).isEqualTo(at(10, 18, 0));
    }

    @Test
    @DisplayName("API 파라미터 문자열과 DB base_at(UTC)이 한 발표분에서 같이 나온다")
    void issueFormats() {
        Issue issue = new Issue(at(10, 5, 0));
        assertThat(issue.baseDate()).isEqualTo("20260910");
        assertThat(issue.baseTime()).isEqualTo("0500");
        assertThat(issue.tmFc()).isEqualTo("202609100500");
        assertThat(issue.issueDate()).isEqualTo(at(10, 0, 0).toLocalDate());
        assertThat(issue.issuedAtUtc()).isEqualTo(at(9, 20, 0));
    }
}
