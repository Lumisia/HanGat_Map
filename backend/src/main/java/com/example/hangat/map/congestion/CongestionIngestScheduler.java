package com.example.hangat.map.congestion;

import com.example.hangat.map.congestion.CongestionIngestService.CongestionIngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 혼잡(집중률) 적재 스케줄 - 매일 03:00 KST, 운영에서만.
 *
 * <p>집중률 예보는 "오늘부터 30일" 창이라 하루라도 거르면 창이 밀려 슬라이더 뒤쪽 날짜가
 * 회색(정보 없음)으로 바뀐다. 그동안은 매일 손으로 {@code POST /admin/ingest/congestion}을 눌러 왔는데
 * 운영에서는 아무도 누르지 않으므로 여기서 자동으로 돈다.
 *
 * <p><b>03:00인 이유</b>: 03:30 날씨({@code WeatherIngestScheduler}) → 04:00 샘플 코스
 * ({@code SampleCourseScheduler}) 순서의 맨 앞이다. 코스 배치가 오늘 기준 혼잡을 읽어야 한다.
 *
 * <p><b>게이트</b>: {@code congestion.ingest.enabled}가 true일 때만 돈다(운영 yaml). dev는 false -
 * 개발 머신마다 밤에 집중률 API를 부르지 않는다. {@code congestion.ingest.on-startup}은 배포 직후
 * 첫 스케줄까지 기다리지 않으려는 운영 편의 플래그다 - 같은 날 재실행은 그날 발표분만 지우고
 * 다시 넣으므로(멱등) 재기동해도 행이 중복되지 않는다.
 *
 * <p><b>재시도</b>: 스케줄 경로는 실패하면 5분 간격으로 최대 3회 시도한다 - 공공데이터포털이 새벽에 잠깐
 * 죽어 있으면(2026-09-06 실측: 집중률 API Read timed out) 하루치가 통째로 빠지는데, 한 번만 성공해도 30일
 * 창이 온전히 복구되므로 같은 날 안의 재시도가 값어치가 있다. 기동 경로는 재시도하지 않는다 -
 * ApplicationReadyEvent 리스너가 잠들면 뒤따르는 날씨·샘플 코스 기동 실행까지 막힌다.
 *
 * <p>끝내 실패해도 던지지 않는다. 어제 발표분이 남아 있고 다음 회차가 다시 시도한다.
 * 구조는 {@code WeatherIngestScheduler}와 같다 - 둘을 나란히 읽으면 된다.
 */
@Component
@org.springframework.context.annotation.Profile("!batch")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "hangat.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CongestionIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(CongestionIngestScheduler.class);

    static final String ZONE = "Asia/Seoul";
    static final String CRON_DAILY = "0 0 3 * * *";
    static final int MAX_ATTEMPTS = 3;
    static final Duration RETRY_DELAY = Duration.ofMinutes(5);

    /** 재시도 사이의 대기. 테스트가 실제로 5분을 자지 않도록 바꿔 끼운다 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final CongestionIngestService ingestService;
    private final boolean enabled;
    private final boolean onStartup;
    private final Sleeper sleeper;

    @Autowired
    public CongestionIngestScheduler(CongestionIngestService ingestService,
                                     @Value("${congestion.ingest.enabled:false}") boolean enabled,
                                     @Value("${congestion.ingest.on-startup:false}") boolean onStartup) {
        this(ingestService, enabled, onStartup, d -> Thread.sleep(d.toMillis()));
    }

    CongestionIngestScheduler(CongestionIngestService ingestService, boolean enabled, boolean onStartup,
                              Sleeper sleeper) {
        this.ingestService = ingestService;
        this.enabled = enabled;
        this.onStartup = onStartup;
        this.sleeper = sleeper;
    }

    @Scheduled(cron = CRON_DAILY, zone = ZONE)
    public void scheduled() {
        run("스케줄", MAX_ATTEMPTS);
    }

    /**
     * ApplicationReadyEvent 리스너의 예외는 기동 실패로 번지므로 {@link #run}이 전부 삼킨다.
     * {@code @Order(0)}: 날씨 기동 적재(1)·샘플 코스 기동 생성(기본 순서 = 마지막)보다 먼저 돌아
     * 코스가 오늘 기준 혼잡을 볼 수 있게 한다.
     */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (onStartup) {
            run("기동", 1);
        }
    }

    /** @return 실행했으면 결과. 게이트가 닫혀 있거나 실패했으면 비어 있다. */
    Optional<CongestionIngestResult> run(String trigger) {
        return run(trigger, 1);
    }

    /** @param attempts 최대 시도 횟수. 사이사이 {@link #RETRY_DELAY}만큼 기다린다 */
    Optional<CongestionIngestResult> run(String trigger, int attempts) {
        if (!enabled) {
            log.debug("혼잡 적재 게이트 닫힘(congestion.ingest.enabled=false) - {} 실행 건너뜀", trigger);
            return Optional.empty();
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return Optional.of(ingestService.ingest());
            } catch (Exception e) {
                if (attempt == attempts) {
                    log.error("혼잡 적재 실패({} {}/{}회) - 어제 발표분은 남아 있고 다음 회차가 다시 시도한다",
                            trigger, attempt, attempts, e);
                    return Optional.empty();
                }
                log.warn("혼잡 적재 실패({} {}/{}회) - {}분 뒤 재시도: {}",
                        trigger, attempt, attempts, RETRY_DELAY.toMinutes(), e.getMessage());
                try {
                    sleeper.sleep(RETRY_DELAY);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
