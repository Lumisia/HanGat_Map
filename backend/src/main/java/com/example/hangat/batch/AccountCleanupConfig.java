package com.example.hangat.batch;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("batch")
@ConditionalOnProperty(name="hangat.batch.job", havingValue="account-cleanup")
public class AccountCleanupConfig {
    @Bean AccountCleanupService accountCleanupService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${hangat.account-cleanup.batch-size:100}") int batchSize) {
        return new AccountCleanupService(jdbc,manager,Clock.systemUTC(),batchSize);
    }
    @Bean ApplicationRunner accountCleanupRunner(AccountCleanupService service) {
        return args -> {
            var result = service.runBatch();
            log.info("회원 정리 결과 deleted={} skipped={} failed={}",result.deleted(),result.skipped(),result.failed());
            if (result.failed() > 0) throw new IllegalStateException("ACCOUNT_CLEANUP_RETRY");
        };
    }
}
