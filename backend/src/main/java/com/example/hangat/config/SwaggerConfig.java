package com.example.hangat.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI(
            @Value("${app.backend-url:http://localhost:8080}")
            String backendUrl) {

        return new OpenAPI()
                .info(new Info()
                        .title("한갓지도 API")
                        .description("""
                                제주 오버투어리즘 분산 코스 추천 서비스 API 문서.

                                - 혼잡 예보는 **날짜 단위**로만 제공한다 (시간대 단위 예측 없음)
                                - 인증이 필요한 API는 우측 Authorize에 발급된 Access Token을 입력합니다.
                                """)
                        .version("v0.1"))
                .servers(List.of(
                        new Server()
                                .url(backendUrl)
                                .description("한갓지도 백엔드 API 서버")
                ))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .name("BearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("발급된 Access Token을 입력하세요.")));
    }
}
