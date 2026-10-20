package com.example.hangat.course.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringJUnitConfig(CourseAiDiagnosticPersistenceTest.Config.class)
class CourseAiDiagnosticPersistenceTest {
    @Configuration
    @ComponentScan(basePackageClasses = CourseAiGenerationService.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*\\.(CourseAiGenerationService|CourseAiResultValidator|DeterministicCourseFallback|CourseAiDiagnosticRecorder)"))
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(
                "jdbc:h2:mem:diagnostic;MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager manager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean CourseAiProvider provider() {
            return input -> { throw new CourseAiException(CourseAiFailureType.RATE_LIMIT,
                    "private-api-key private-prompt private-response")
                    .withDiagnostic(new CourseAiDiagnostic(CourseAiFailureType.RATE_LIMIT,
                            CourseAiDiagnostic.Phase.HTTP_EXCHANGE, 429, "RESOURCE_EXHAUSTED",
                            "RATE_LIMIT_EXCEEDED", null, "gemini-3.6-flash", 3, 120)); };
        }
    }

    @Autowired CourseAiGenerationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired CourseAiDiagnosticRecorder recorder;

    @BeforeEach void schema() {
        jdbc.execute("DROP TABLE IF EXISTS course_ai_diagnostics");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V15__course_ai_diagnostics.sql"))
                .execute(jdbc.getDataSource());
    }

    @Test void providerFailureIsStoredEvenWhenFallbackSucceeds() {
        assertThat(service.generate(input(true)).days()).hasSize(1);
        var rows = jdbc.queryForList("SELECT * FROM course_ai_diagnostics");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("HTTP_STATUS", 429)
                .containsEntry("GOOGLE_STATUS", "RESOURCE_EXHAUSTED")
                .containsEntry("GOOGLE_REASON", "RATE_LIMIT_EXCEEDED")
                .containsEntry("ATTEMPTS", 3).containsEntry("OUTCOME", "FALLBACK_SUCCEEDED");
        assertThat(rows.toString()).doesNotContain("private-api-key", "private-prompt", "private-response");
    }

    @Test void originalProviderDiagnosticSurvivesFallbackFailureAndOuterRollback() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.generate(input(false))).isInstanceOf(CourseAiException.class);
            status.setRollbackOnly();
        });
        var rows = jdbc.queryForList("SELECT * FROM course_ai_diagnostics WHERE call_kind='INITIAL'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("HTTP_STATUS", 429)
                .containsEntry("OUTCOME", "FALLBACK_FAILED");
        assertThat(jdbc.queryForList("SELECT * FROM course_ai_diagnostics WHERE call_kind='FALLBACK'"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("OUTCOME","FALLBACK_FAILED"));
    }

    @Test void fallbackValidationCodeIsStoredWithoutPrivateMessages() {
        var brokenFallback = new DeterministicCourseFallback() {
            @Override public CourseAiResultDto generate(CourseAiInputDto input) {
                throw new CourseAiValidationException(CourseAiValidationCode.AI_RESULT_TRAVEL_TIME_OVERLAP,
                        "private-api-key private-prompt private-response");
            }
        };
        var generating=new CourseAiGenerationService(actual -> { throw new CourseAiException(CourseAiFailureType.RATE_LIMIT,"private-response"); },
                new CourseAiResultValidator(),brokenFallback,recorder);
        assertThatThrownBy(()->generating.generate(input(true))).isInstanceOf(CourseAiException.class);
        var rows=jdbc.queryForList("SELECT * FROM course_ai_diagnostics WHERE call_kind='FALLBACK'");
        assertThat(rows).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("VALIDATION_CODE","AI_RESULT_TRAVEL_TIME_OVERLAP").containsEntry("OUTCOME","FALLBACK_FAILED"));
        assertThat(rows.toString()).doesNotContain("private-api-key","private-prompt","private-response");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT trace_id) FROM course_ai_diagnostics",Integer.class)).isEqualTo(1);
    }
    @Test void missingCafeCandidateGetsSpecificFallbackCode() {
        var base=input(true);
        var cafeInput=new CourseAiInputDto(base.contractVersion(),base.trip(),
                new CourseAiInputDto.SoftPreferencesDto(List.of(),List.of("CAFE")),base.hardConstraints(),
                base.accommodation(),base.candidates(),base.weatherFactSets(),base.travelFacts(),base.compatibility());
        assertThatThrownBy(()->service.generate(cafeInput)).isInstanceOf(CourseAiException.class);
        assertThat(jdbc.queryForList("SELECT * FROM course_ai_diagnostics WHERE call_kind='FALLBACK'"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("VALIDATION_CODE","AI_RESULT_STYLE_CANDIDATE_MISSING"));
    }

    @Test void missingDiagnosticTableDoesNotBreakFallbackOrLogSensitiveCause() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CourseAiDiagnosticRecorder.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            jdbc.execute("DROP TABLE course_ai_diagnostics");
            assertThat(service.generate(input(true)).days()).hasSize(1);
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).startsWith("AI_DIAGNOSTIC_WRITE_FAILED traceId="));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("private-api-key", "private-prompt", "private-response", "INSERT INTO", "jdbc:");
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void jobCorrelationIsClearedBeforeTheWorkerIsReused() {
        UUID job = UUID.randomUUID();
        try (var ignored = CourseAiDiagnosticContext.forJob(job)) {
            service.generate(input(true));
        }
        service.generate(input(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_ai_diagnostics WHERE job_id = ?", Integer.class, job.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_ai_diagnostics WHERE job_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT trace_id) FROM course_ai_diagnostics", Integer.class)).isEqualTo(2);
    }

    @Test void retentionOnlyRemovesOldDiagnosticRows() {
        service.generate(input(true));
        String oldId = jdbc.queryForObject("SELECT id FROM course_ai_diagnostics", String.class);
        jdbc.update("UPDATE course_ai_diagnostics SET occurred_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(31, java.time.temporal.ChronoUnit.DAYS)), oldId);
        service.generate(input(true));
        recorder.purgeExpired();
        assertThat(jdbc.queryForList("SELECT id FROM course_ai_diagnostics", String.class))
                .hasSize(1).doesNotContain(oldId);
    }

    @Test void correctedCourseKeepsInitialValidationCode() {
        var generating = new CourseAiGenerationService(correctionProvider(true), new CourseAiResultValidator(),
                new DeterministicCourseFallback(), recorder);
        assertThat(generating.generate(input(true)).days()).hasSize(1);
        var rows = jdbc.queryForList("SELECT * FROM course_ai_diagnostics");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("OUTCOME", "CORRECTION_SUCCEEDED")
                .containsEntry("VALIDATION_CODE", "AI_RESULT_START_TIME_FORMAT_INVALID");
    }

    @Test void correctionFailureKeepsBothAttemptsOnTheSameTrace() {
        var generating = new CourseAiGenerationService(correctionProvider(false), new CourseAiResultValidator(),
                new DeterministicCourseFallback(), recorder);
        assertThat(generating.generate(input(true)).days()).hasSize(1);
        var rows = jdbc.queryForList("SELECT * FROM course_ai_diagnostics ORDER BY call_kind");
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("OUTCOME", "FALLBACK_SUCCEEDED"));
        assertThat(rows.get(0)).containsEntry("CALL_KIND", "CORRECTION").containsEntry("HTTP_STATUS", 503);
        assertThat(rows.get(1)).containsEntry("CALL_KIND", "INITIAL")
                .containsEntry("VALIDATION_CODE", "AI_RESULT_START_TIME_FORMAT_INVALID");
        assertThat(rows.get(0).get("TRACE_ID")).isEqualTo(rows.get(1).get("TRACE_ID"));
    }

    private CourseAiProvider correctionProvider(boolean succeeds) {
        return new CourseAiProvider() {
            @Override public CourseAiResultDto generate(CourseAiInputDto input) {
                throw new CourseAiValidationException(CourseAiValidationCode.AI_RESULT_START_TIME_FORMAT_INVALID,
                        "private-response");
            }
            @Override public CourseAiResultDto generateCorrection(CourseAiInputDto input, CourseAiResultDto previous,
                    CourseAiValidationCode code, String message) {
                if (!succeeds) throw new CourseAiException(CourseAiFailureType.TEMPORARILY_UNAVAILABLE, "private-response")
                        .withDiagnostic(new CourseAiDiagnostic(CourseAiFailureType.TEMPORARILY_UNAVAILABLE,
                                CourseAiDiagnostic.Phase.HTTP_EXCHANGE, 503, "UNAVAILABLE", null, null,
                                "gemini-3.6-flash", 3, 100));
                return new CourseAiResultDto("1.0", List.of(new CourseAiResultDto.DayDto(LocalDate.of(2026, 9, 18),
                        List.of(new CourseAiResultDto.ItemDto("candidate-a", java.time.LocalTime.of(10, 0), "소개")))));
            }
        };
    }

    @Test void rawHttpFailureIsNotPersistedLoggedOrReturnedToUser() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CourseAiDiagnosticRecorder.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var builder = org.springframework.web.client.RestClient.builder().baseUrl("http://gemini.test");
            var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://gemini.test/models/gemini-3.6-flash:generateContent"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                            .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body("""
                                {"error":{"code":403,"message":"private-response private-prompt",
                                "status":"PERMISSION_DENIED","details":[{"reason":"private-api-key","metadata":{"private":"private-response"}}]}}
                                """));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var provider = new GeminiCourseAiProvider(builder.build(), new GeminiProperties("http://gemini.test",
                    "private-api-key", "gemini-3.6-flash", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)),
                    new CourseAiPrompt(mapper), mapper);
            var generating = new CourseAiGenerationService(provider, new CourseAiResultValidator(), new DeterministicCourseFallback(), recorder);
            CourseAiException failure = catchThrowableOfType(CourseAiException.class, () -> generating.generate(input(false)));
            assertThat(failure).isNotNull();
            var rows = jdbc.queryForList("SELECT * FROM course_ai_diagnostics");
            assertThat(rows).hasSize(2);
            assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("CALL_KIND","INITIAL")
                    .containsEntry("HTTP_STATUS", 403).containsEntry("GOOGLE_REASON", "OTHER"));
            String userBody = mapper.writeValueAsString(new com.example.hangat.common.exception.GlobalExceptionHandler()
                    .handleCourseAiException(failure).getBody());
            assertThat(rows.toString() + userBody + appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList())
                    .doesNotContain("private-api-key", "private-prompt", "private-response");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertThat(userBody).doesNotContain("google", "PERMISSION_DENIED", "diagnostic", "trace_id");
            server.verify();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private CourseAiInputDto input(boolean withCandidate) {
        var candidate = new CourseAiInputDto.CandidateFactDto(
                new CourseAiInputDto.PlaceIdentityDto("candidate-a", null, "KTO", "a"),
                "장소", "주소", 33.4, 126.6, null, "EAST", null, List.of(), List.of(), null);
        return new CourseAiInputDto("1.0", new CourseAiInputDto.TripConditionDto(
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18), 2,
                com.example.hangat.course.model.Transport.RENTAL_CAR),
                new CourseAiInputDto.UserPreferencesDto(List.of(), List.of(), List.of(), List.of(), null),
                withCandidate ? List.of(candidate) : List.of(), List.of(), null);
    }
}
