package com.example.hangat.config;

import com.example.hangat.config.security.jwt.JwtAuthenticationEntryPoint;
import com.example.hangat.config.security.jwt.JwtAuthenticationFilter;
import com.example.hangat.config.security.jwt.JwtProvider;
import com.example.hangat.config.security.oauth.OAuthAuthenticationFailureHandler;
import com.example.hangat.config.security.oauth.OAuthAuthenticationSuccessHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * OAuth 로그인용 세션 체인과 JWT API용 무상태 체인을 분리한다.
 *
 * OAuth authorization request의 state는 공급자 왕복 동안만 세션에 보관하고,
 * 일반 API는 세션을 만들지 않은 채 JWT로 인증한다.
 */
@EnableWebSecurity
@Configuration
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(
            @Value("${app.cors.allowed-origins:http://localhost:4173,http://localhost:5173}")
            String allowedOrigins) {

        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();

        if (this.allowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins 설정이 필요합니다."
            );
        }
    }

    // ────────────────────────── OAuth 세션 체인 ──────────────────────────

    /**
     * Google·Kakao 공급자 왕복에만 사용하는 보안 체인.
     * 테스트 프로필은 실제 공급자 등록 정보가 없으므로 생성하지 않는다.
     */
    @Bean
    @Order(1)
    @Profile("!test")
    public SecurityFilterChain oauthSecurityFilterChain(
            HttpSecurity http,
            OAuthAuthenticationSuccessHandler successHandler,
            OAuthAuthenticationFailureHandler failureHandler) throws Exception {

        http.securityMatcher(
                "/oauth2/**",
                "/login/oauth2/**"
        );

        http.cors(cors ->
                cors.configurationSource(corsConfigurationSource()));
        http.csrf(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);
        http.sessionManagement(session ->
                session.sessionCreationPolicy(
                        SessionCreationPolicy.IF_REQUIRED
                ));
        http.authorizeHttpRequests(auth ->
                auth.anyRequest().permitAll());
        http.oauth2Login(oauth -> oauth
                .successHandler(successHandler)
                .failureHandler(failureHandler));

        return http.build();
    }

    // ────────────────────────── JWT API 체인 ──────────────────────────

    /**
     * 일반 REST API용 무상태 보안 체인.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            JwtAuthenticationEntryPoint entryPoint) throws Exception {

        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        http.csrf(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);
        http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.authorizeHttpRequests(auth -> auth
                // 예외 처리중 /error가 다시 인증에 막히는 것을 방지
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                // 운영 및 API 문서
                .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**"
                ).permitAll()
                .requestMatchers(
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                ).permitAll()

                // 비회원 공개 API
                .requestMatchers("/main/**").permitAll()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/places",
                        "/places/**",
                        "/crowd/**"
                ).permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/users/check-nickname"
                ).permitAll()
                .requestMatchers(
                        HttpMethod.POST,
                        "/courses"
                ).permitAll()
                .requestMatchers(
                        HttpMethod.POST,
                        "/courses/*/accommodations/search"
                ).permitAll()
                // READY 숙소 변경은 서비스가 course-scoped claim proof를 검증한다.
                // SAVED 코스는 같은 공개 경로에서도 로그인 owner만 통과한다.
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/courses/*/accommodation"
                ).permitAll()
                // 코스 상세는 비회원 임시 코스·메인 샘플을 열어야 해서 공개.
                // 소유자가 있는 저장 코스는 서비스가 본인 여부를 검증한다(3307)
                .requestMatchers(
                        HttpMethod.GET,
                        "/courses/*"
                ).permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/courses/*/routes/car"
                ).permitAll()
                // 스왑(#과밀지역우회)은 비회원 코스에도 열려 있어야 한다 - 생성이 비로그인 허용이라
                // 생성 직후 대안 교체까지가 한 흐름이다. 소유자 있는 저장 코스는 서비스가 본인 검증
                .requestMatchers(
                        HttpMethod.POST,
                        "/courses/*/items/*/swap"
                ).permitAll()

                // 그 외 API는 인증 필요
                // 공개 후기 여부/미첨부 사진의 소유권은 ReviewPhotoService에서 검사한다.
                .requestMatchers(HttpMethod.GET,
                        "/media/reviews/*/*", "/uploads/reviews/*").permitAll()
                .requestMatchers(HttpMethod.HEAD,
                        "/media/reviews/*/*", "/uploads/reviews/*").permitAll()
                .anyRequest().authenticated()
        );
        http.exceptionHandling(exception ->
                exception.authenticationEntryPoint(entryPoint));

        http.addFilterBefore(
                jwtFilter,
                UsernamePasswordAuthenticationFilter.class
        );

        return http.build();
    }

    // ────────────────────────── 공통 보안 빈 ──────────────────────────

    /**
     * JWT 필터도 스프링 빈으로 관리해 생성 책임과 의존성 주입을 설정에 모은다.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtProvider jwtProvider,
            JwtAuthenticationEntryPoint entryPoint) {

        return new JwtAuthenticationFilter(jwtProvider, entryPoint);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
