package com.example.hangat.course.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 샘플 코스 야간 배치 트리거 - 매일 새벽 4시(KST), 내일 출발 코스 3개 재생성.
 *
 * <p><b>왜 4시인가</b>: 집중률·날씨 적재가 먼저 있어야 하므로 적재(3시 예정, 후경님과 협의) 뒤로 뒀다.
 * 적재가 아직 수동인 동안에도 이 배치는 안전하다 - 예보가 없으면 스킵하고 어제 코스가 남는다.
 *
 * <p>기동 시 생성(generate-on-startup)은 dev 편의 기능이다 - 로컬에서 스케줄 시각을 기다리지 않고
 * 카드를 보기 위한 것. 재기동마다 행이 쌓이지 않는 건 생성기 쪽 프리셋 단위 멱등이 보장한다
 * (같은 출발일 READY가 있는 프리셋만 스킵 - 부분 실패분은 재기동으로 채워진다).
 */
@Component
@org.springframework.context.annotation.Profile("!batch")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "hangat.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SampleCourseScheduler {

    private static final Logger log = LoggerFactory.getLogger(SampleCourseScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SampleCourseGenerator generator;
    private final boolean generateOnStartup;

    public SampleCourseScheduler(SampleCourseGenerator generator,
                                 @Value("${sample-courses.generate-on-startup:false}") boolean generateOnStartup) {
        this.generator = generator;
        this.generateOnStartup = generateOnStartup;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void nightly() {
        generator.generate(tomorrow());
    }

    /** 편의 기능의 예외가 부팅을 죽이면 안 된다 - ApplicationReadyEvent 리스너의 예외는 기동 실패로 번진다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!generateOnStartup) {
            return;
        }
        try {
            generator.generate(tomorrow());
        } catch (Exception e) {
            log.error("샘플 코스 기동 생성 실패 - 새벽 배치가 다시 시도한다", e);
        }
    }

    /** 서버 시계가 UTC여도(OCI 기본) 제주 기준 '내일'이 되도록 KST 고정. */
    private LocalDate tomorrow() {
        return LocalDate.now(KST).plusDays(1);
    }
}
