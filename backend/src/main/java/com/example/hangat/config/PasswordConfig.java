package com.example.hangat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호 해시 설정 - 웹 보안 필터와 분리해 비웹 실행에서도 서비스 의존성을 유지한다. */
@Configuration
public class PasswordConfig {
    /** 기존 API와 동일한 BCrypt 인코더를 사용한다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
