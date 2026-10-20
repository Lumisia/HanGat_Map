package com.example.hangat;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import com.example.hangat.batch.BatchComponentExcludeFilter;

@ConfigurationPropertiesScan
@SpringBootApplication
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = BatchComponentExcludeFilter.class)
})
@EnableAsync
public class HangatApplication {

    public static void main(String[] args) {
        loadDotenv();
        var context = SpringApplication.run(HangatApplication.class, args);
        // ApplicationRunner 실패는 main 밖으로 전파되어 비정상 종료한다.
        // 정상 배치도 DB 풀·비동기 스레드를 정리한 뒤 JVM을 종료해야 Job이 완료된다.
        if (context.getEnvironment().acceptsProfiles(Profiles.of("batch"))) {
            System.exit(SpringApplication.exit(context));
        }
    }

    /**
     * backend/.env 파일을 시스템 프로퍼티로 로드한다 (yaml의 ${...} 치환용).
     * - .env가 없으면 조용히 넘어간다 (배포 환경은 실제 환경변수 사용)
     * - 이미 설정된 실제 환경변수가 항상 우선한다
     */
    private static void loadDotenv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> {
            if (System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }
}
