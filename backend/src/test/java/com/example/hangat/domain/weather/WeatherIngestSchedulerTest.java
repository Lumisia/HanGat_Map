package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.WeatherIngestService.WeatherIngestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 스케줄러는 게이트·실패 삼킴·시각 세 가지만 책임진다. 적재 자체는 WeatherIngestServiceTest. */
class WeatherIngestSchedulerTest {

    private static final WeatherIngestResult OK =
            new WeatherIngestResult(4, 16, 16, 32, 0, 0, false, "202609100500", "202609100600");

    private final WeatherIngestService service = mock(WeatherIngestService.class);

    @Test
    @DisplayName("게이트가 닫혀 있으면(dev 기본) 스케줄도 기동 실행도 기상청을 부르지 않는다")
    void disabledGateSkipsEverything() {
        WeatherIngestScheduler scheduler = new WeatherIngestScheduler(service, false, true);

        scheduler.scheduled();
        scheduler.onStartup();

        verify(service, never()).ingest();
        assertThat(scheduler.run("테스트")).isEmpty();
    }

    @Test
    @DisplayName("게이트가 열려 있으면 스케줄이 적재를 실행하고 결과를 돌려준다")
    void enabledRunsIngest() {
        given(service.ingest()).willReturn(OK);
        WeatherIngestScheduler scheduler = new WeatherIngestScheduler(service, true, false);

        scheduler.scheduled();

        verify(service, times(1)).ingest();
        assertThat(scheduler.run("테스트")).contains(OK);
    }

    @Test
    @DisplayName("기동 실행은 on-startup 플래그가 있을 때만 - 운영 배포 직후 첫 스케줄까지 기다리지 않으려는 편의")
    void startupOnlyWithFlag() {
        given(service.ingest()).willReturn(OK);

        new WeatherIngestScheduler(service, true, false).onStartup();
        verify(service, never()).ingest();

        new WeatherIngestScheduler(service, true, true).onStartup();
        verify(service, times(1)).ingest();
    }

    @Test
    @DisplayName("적재 실패는 던지지 않는다 - 기동 리스너의 예외는 부팅 실패로 번지고, 스케줄 스레드는 다음 회차가 재시도한다")
    void swallowsFailure() {
        given(service.ingest()).willThrow(new IllegalStateException("db down"));
        WeatherIngestScheduler scheduler = new WeatherIngestScheduler(service, true, true);

        assertThatCode(scheduler::scheduled).doesNotThrowAnyException();
        assertThatCode(scheduler::onStartup).doesNotThrowAnyException();
        assertThat(scheduler.run("테스트")).isEmpty();
    }

    @Test
    @DisplayName("03:30은 04:00 샘플 코스 배치보다 앞이고, 06:30은 05시 단기·06시 중기 발표분이 올라온 뒤다")
    void cronTimesLineUpWithIssueTimesAndCourseBatch() {
        LocalDateTime midnight = LocalDateTime.of(2026, 9, 10, 0, 0);
        LocalDateTime beforeDawn = CronExpression.parse(WeatherIngestScheduler.CRON_BEFORE_DAWN).next(midnight);
        LocalDateTime morning = CronExpression.parse(WeatherIngestScheduler.CRON_MORNING).next(midnight);
        LocalDateTime courseBatch = CronExpression.parse("0 0 4 * * *").next(beforeDawn);

        assertThat(beforeDawn).isEqualTo(midnight.withHour(3).withMinute(30));
        assertThat(morning).isEqualTo(midnight.withHour(6).withMinute(30));
        assertThat(courseBatch).isEqualTo(midnight.withHour(4));
        assertThat(KmaIssueTimes.shortTermFor(beforeDawn).issuedAtKst()).isEqualTo(midnight.withHour(2));
        assertThat(KmaIssueTimes.shortTermFor(morning).issuedAtKst()).isEqualTo(midnight.withHour(5));
        assertThat(KmaIssueTimes.midFor(morning).issuedAtKst()).isEqualTo(midnight.withHour(6));
    }
}
