package com.example.hangat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 알림 전송과 AI 대기열 확인용 스케줄러.
 *
 * 일일 데이터 적재를 k3s CronJob으로 옮겨도
 * 실시간 AI 작업 처리는 API 프로세스에서 계속 실행한다.
 */
@Configuration
@EnableScheduling
@Profile("!test & !batch")
@ConditionalOnExpression(
        "${hangat.async.enabled:false} or "
                + "${hangat.notifications.enabled:false}"
)
public class NotificationSchedulingConfig {

    @Bean(name = "alarmScheduler")
    public ThreadPoolTaskScheduler alarmScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("alarm-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);

        return scheduler;
    }
}
