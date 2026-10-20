package com.example.hangat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/** 배포 식별 정보 - 환경변수 전체 대신 공개 가능한 커밋과 이미지 태그만 제공한다. */
@Configuration(proxyBeanMethods = false)
@Profile("!batch")
public class DeploymentInfoConfig {
    /** Jenkins에서 지정한 이미지와 커밋을 /actuator/info로 확인한다. 로컬은 unknown이다. */
    @Bean
    public InfoContributor deploymentInfo(
            @Value("${DEPLOYMENT_REVISION:unknown}") String revision,
            @Value("${DEPLOYMENT_IMAGE_TAG:unknown}") String imageTag) {
        return builder -> builder.withDetail("deployment", Map.of("revision", revision, "imageTag", imageTag));
    }
}
