package com.example.hangat.map.congestion;

import com.example.hangat.map.congestion.CongestionIngestService.CongestionIngestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 스케줄러는 게이트·실패 삼킴·시각 세 가지만 책임진다. 적재 자체는 CongestionIngestService 쪽 테스트. */
class CongestionIngestSchedulerTest {

    /** 2026-09-05 실측과 같은 모양 - 340곳 × 30일 */
    private static final CongestionIngestResult OK =
            new CongestionIngestResult(13440, 10200, 0, 340, 108, 336, 30, "2026-09-05T00:00");

    private final CongestionIngestService service = mock(CongestionIngestService.class);
    /** 실제로 자지 않고 대기 시간만 기록한다 - 5분 × 2회를 테스트가 기다릴 수는 없다 */
    private final List<Duration> sleeps = new ArrayList<>();

    private CongestionIngestScheduler scheduler(boolean enabled, boolean onStartup) {
        return new CongestionIngestScheduler(service, enabled, onStartup, sleeps::add);
    }

    @Test
    @DisplayName("게이트가 닫혀 있으면(dev 기본) 스케줄도 기동 실행도 집중률 API를 부르지 않는다")
    void disabledGateSkipsEverything() {
        CongestionIngestScheduler scheduler = scheduler(false, true);

        scheduler.scheduled();
        scheduler.onStartup();

        verify(service, never()).ingest();
        assertThat(scheduler.run("테스트")).isEmpty();
    }

    @Test
    @DisplayName("게이트가 열려 있으면 스케줄이 적재를 실행하고 결과를 돌려준다")
    void enabledRunsIngest() {
        given(service.ingest()).willReturn(OK);
        CongestionIngestScheduler scheduler = scheduler(true, false);

        scheduler.scheduled();

        verify(service, times(1)).ingest();
        assertThat(scheduler.run("테스트")).contains(OK);
    }

    @Test
    @DisplayName("기동 실행은 on-startup 플래그가 있을 때만 - 운영 배포 직후 첫 스케줄까지 기다리지 않으려는 편의")
    void startupOnlyWithFlag() {
        given(service.ingest()).willReturn(OK);

        scheduler(true, false).onStartup();
        verify(service, never()).ingest();

        scheduler(true, true).onStartup();
        verify(service, times(1)).ingest();
    }

    @Test
    @DisplayName("적재 실패는 던지지 않는다 - 기동 리스너의 예외는 부팅 실패로 번지고, 스케줄은 다음 회차가 재시도한다")
    void swallowsFailure() {
        given(service.ingest()).willThrow(new IllegalStateException("quota exceeded"));
        CongestionIngestScheduler scheduler = scheduler(true, true);

        assertThatCode(scheduler::scheduled).doesNotThrowAnyException();
        assertThatCode(scheduler::onStartup).doesNotThrowAnyException();
        assertThat(scheduler.run("테스트")).isEmpty();
    }

    @Test
    @DisplayName("스케줄 경로는 실패하면 5분 간격으로 3회까지 시도한다 - 한 번만 성공해도 30일 창이 복구되므로")
    void scheduledRetriesUpToThreeTimes() {
        given(service.ingest())
                .willThrow(new IllegalStateException("Read timed out"))
                .willThrow(new IllegalStateException("Read timed out"))
                .willReturn(OK);
        CongestionIngestScheduler scheduler = scheduler(true, false);

        scheduler.scheduled();

        verify(service, times(3)).ingest();
        assertThat(sleeps).containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("3회 모두 실패하면 포기한다 - 다음 날 03:00이 다시 시도")
    void givesUpAfterThreeFailures() {
        given(service.ingest()).willThrow(new IllegalStateException("portal down"));
        CongestionIngestScheduler scheduler = scheduler(true, false);

        assertThat(scheduler.run("스케줄", CongestionIngestScheduler.MAX_ATTEMPTS)).isEmpty();
        verify(service, times(3)).ingest();
        assertThat(sleeps).hasSize(2);
    }

    @Test
    @DisplayName("기동 실행은 재시도하지 않는다 - 기동 리스너가 잠들면 날씨·샘플 코스 기동 실행까지 막힌다")
    void startupDoesNotRetry() {
        given(service.ingest()).willThrow(new IllegalStateException("portal down"));
        CongestionIngestScheduler scheduler = scheduler(true, true);

        scheduler.onStartup();

        verify(service, times(1)).ingest();
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("03:00은 03:30 날씨 적재와 04:00 샘플 코스 배치보다 앞이다 - 코스가 오늘 기준 혼잡을 읽어야 한다")
    void cronRunsBeforeWeatherAndCourseBatches() {
        LocalDateTime midnight = LocalDateTime.of(2026, 9, 10, 0, 0);
        LocalDateTime congestion = CronExpression.parse(CongestionIngestScheduler.CRON_DAILY).next(midnight);
        LocalDateTime weather = CronExpression.parse("0 30 3 * * *").next(midnight);
        LocalDateTime courseBatch = CronExpression.parse("0 0 4 * * *").next(midnight);

        assertThat(congestion).isEqualTo(midnight.withHour(3));
        assertThat(congestion).isBefore(weather);
        assertThat(congestion).isBefore(courseBatch);
    }
}
