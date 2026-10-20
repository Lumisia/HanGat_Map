package com.example.hangat.map.detail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** KTO 소개글 원문 정리 - 내용은 건드리지 않고 표기(태그·엔티티·빈 줄)만 손본다 */
class OverviewTextCleanTest {

    @Test
    void br은_줄바꿈_나머지_태그는_제거() {
        assertThat(OverviewIngestService.clean("첫 줄<br>둘째 줄<br/>셋째 <b>강조</b> 줄"))
                .isEqualTo("첫 줄\n둘째 줄\n셋째 강조 줄");
    }

    @Test
    void 엔티티는_복원하고_빈_줄은_두_줄까지만() {
        assertThat(OverviewIngestService.clean("A &amp; B&nbsp;C<br><br><br><br>D &quot;E&quot;"))
                .isEqualTo("A & B C\n\nD \"E\"");
    }

    @Test
    void 비거나_태그뿐이면_null() {
        assertThat(OverviewIngestService.clean(null)).isNull();
        assertThat(OverviewIngestService.clean("  ")).isNull();
        assertThat(OverviewIngestService.clean("<br><p></p>")).isNull();
    }
}
