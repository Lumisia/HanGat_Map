package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.WeatherIngestService.WeatherIngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 날씨 적재 스케줄 - 하루 두 번, 운영에서만.
 *
 * <ul>
 *   <li><b>03:30</b> 02시 발표분으로 오늘~+3일을 채운다. 04:00 샘플 코스 배치({@code SampleCourseScheduler})가
 *       비 예보를 보고 실내를 우선 배치하므로 그 앞에 둔다</li>
 *   <li><b>06:30</b> 05시 단기·06시 중기 발표분 - 메인 주간 날씨의 아침 갱신. 발표분 선택 규칙은 {@link KmaIssueTimes}</li>
 * </ul>
 *
 * <p><b>게이트</b>: {@code weather.ingest.enabled}가 true일 때만 돈다(운영 yaml). 개발 머신마다 밤에 기상청을 부르지 않게
 * dev는 false이고 수동 실행은 {@code POST /admin/ingest/weather}(dev 전용)로 한다.
 * {@code weather.ingest.on-startup}은 배포 직후 첫 스케줄까지 기다리지 않으려는 운영 편의 플래그다 -
 * 발표분 단위로 멱등이라 재기동해도 행이 중복되지 않는다.
 *
 * <p>인스턴스가 둘이면 둘 다 돈다. 같은 발표분을 같은 값으로 다시 넣는 것이라 결과는 같고 API 호출만 두 배다 -
 * 복제본을 늘리면 리더 선출이나 잡 분리가 필요하다.
 *
 * <p>실패해도 던지지 않는다. 기존 발표 버전은 남아 있고 다음 스케줄이 다시 시도한다 - 한 번의 기상청 장애가
 * 화면을 비우지 않게.
 */
@Component
public class WeatherIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeatherIngestScheduler.class);

    static final String ZONE = "Asia/Seoul";
    static final String CRON_BEFORE_DAWN = "0 30 3 * * *";
    static final String CRON_MORNING = "0 30 6 * * *";

    private final WeatherIngestService ingestService;
    private final boolean enabled;
    private final boolean onStartup;

    public WeatherIngestScheduler(WeatherIngestService ingestService,
                                  @Value("${weather.ingest.enabled:false}") boolean enabled,
                                  @Value("${weather.ingest.on-startup:false}") boolean onStartup) {
        this.ingestService = ingestService;
        this.enabled = enabled;
        this.onStartup = onStartup;
    }

    @Scheduled(cron = CRON_BEFORE_DAWN, zone = ZONE)
    @Scheduled(cron = CRON_MORNING, zone = ZONE)
    public void scheduled() {
        run("스케줄");
    }

    /**
     * ApplicationReadyEvent 리스너의 예외는 기동 실패로 번지므로 {@link #run}이 전부 삼킨다.
     * {@code @Order(1)}: 같은 이벤트를 듣는 샘플 코스 기동 생성(기본 순서 = 마지막)보다 먼저 돌아
     * 코스가 비 예보와 날씨 스냅숏을 볼 수 있게 한다. 마스터 초기화(ApplicationRunner)는 이 이벤트 전에 끝난다.
     */
    @Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (onStartup) {
            run("기동");
        }
    }

    /** @return 실행했으면 결과. 게이트가 닫혀 있거나 실패했으면 비어 있다. */
    Optional<WeatherIngestResult> run(String trigger) {
        if (!enabled) {
            log.debug("날씨 적재 게이트 닫힘(weather.ingest.enabled=false) - {} 실행 건너뜀", trigger);
            return Optional.empty();
        }
        try {
            return Optional.of(ingestService.ingest());
        } catch (Exception e) {
            log.error("날씨 적재 실패({}) - 기존 발표 버전은 남아 있고 다음 스케줄이 다시 시도한다", trigger, e);
            return Optional.empty();
        }
    }
}
