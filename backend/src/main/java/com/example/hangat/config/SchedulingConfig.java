package com.example.hangat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 활성화 - 샘플 코스 야간 배치(SampleCourseScheduler)가 쓴다.
 *
 * <p>test 프로필 제외: 테스트 컨텍스트가 오래 살아 있을 때 cron이 끼어들면
 * 시드 데이터가 오염된다. 배치 로직 검증은 generate()를 직접 부르는 테스트로 한다.
 */
@Configuration
@EnableScheduling
@Profile("!test & !batch")
@ConditionalOnProperty(name = "hangat.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
