package com.example.hangat.batch;

import com.example.hangat.HangatApplication;
import com.example.hangat.course.CourseAccommodationService;
import com.example.hangat.course.CourseService;
import com.example.hangat.map.detail.OverviewIngestService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.hangat.common.storage.cleanup.MediaCleanupJobService;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 운영 배치의 실제 컴포넌트 구성을 검사한다. 외부 API 호출과 운영 DB 접속은 하지 않는다. */
class BatchApplicationContextTest {

    @ParameterizedTest
    @ValueSource(strings = {"places", "media-cleanup", "account-cleanup", "account-media-cleanup"})
    void prodBatchStartsWithoutApiOnlyAccommodationService(String job) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(HangatApplication.class)
                .withPropertyValues(
                        "spring.profiles.active=prod,batch",
                        "spring.datasource.url=jdbc:h2:mem:batch-startup;MODE=MariaDB;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "weather.service-key=test-key",
                        "weather.base-url=http://localhost",
                        "public-api.service-key=test-key",
                        "public-api.tour-base-url=http://localhost",
                        "tour-api.service-key=test-key",
                        "tour-api.base-url=http://localhost",
                        "congestion-api.service-key=test-key",
                        "congestion-api.base-url=http://localhost",
                        "app.storage.minio.endpoint=http://localhost:9000",
                        "app.storage.minio.bucket=test-bucket",
                        "app.storage.minio.access-key=test-access",
                        "app.storage.minio.secret-key=test-secret",
                        "hangat.batch.job=" + job)
                // 컨텍스트 초기화까지만 수행하므로 ApplicationRunner의 실제 적재는 실행하지 않는다.
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CourseAccommodationService.class);
                    assertThat(context).hasSingleBean(CourseService.class);
                    if (job.equals("media-cleanup") || job.equals("account-media-cleanup")) {
                        assertThat(context).doesNotHaveBean(BatchJobRunner.class);
                        assertThat(context).hasSingleBean(MediaCleanupJobService.class);
                    } else if (job.equals("account-cleanup")) {
                        assertThat(context).doesNotHaveBean(BatchJobRunner.class);
                        assertThat(context).doesNotHaveBean(MediaCleanupJobService.class);
                        assertThat(context).hasSingleBean(AccountCleanupService.class);
                        assertThat(context).hasBean("accountCleanupRunner");
                    } else {
                        assertThat(context).hasSingleBean(BatchJobRunner.class);
                        assertThat(context).doesNotHaveBean(MediaCleanupJobService.class);
                    }
                    assertThat(context).hasSingleBean(OverviewIngestService.class);
                });
    }
}
