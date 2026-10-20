package com.example.hangat.course;

import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.repository.AsyncCourseJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AsyncCourseHistoryTest {
    private JdbcTemplate jdbc;
    private AsyncCourseService service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:history_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE course_generation_jobs (id VARCHAR(40) PRIMARY KEY, user_id BIGINT, status VARCHAR(20), course_id BIGINT, error_code VARCHAR(30), start_date DATE, end_date DATE, created_at TIMESTAMP)");
        service = new AsyncCourseService(new AsyncCourseJobRepository(jdbc), new ObjectMapper(), null, null, null, null, null, mock(PlatformTransactionManager.class));
    }
    private void rows(int count, long user) {
        for (int i = 0; i < count; i++) jdbc.update("INSERT INTO course_generation_jobs VALUES (?, ?, 'SUCCEEDED', NULL, NULL, '2026-09-20', '2026-09-22', ?)",
                "%d-%03d".formatted(user, i), user, Timestamp.valueOf("2026-09-19 23:50:00"));
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
    @Test void emptyAndBeyondLastPageRemainEmpty() {
        assertThat(items(service.recent(6L, 0, 5))).isEmpty(); rows(1, 6);
        assertThat(items(service.recent(6L, 0, 5))).hasSize(1);
        assertThat(service.recent(6L, 1, 5)).containsEntry("hasNext", false);
        assertThat(items(service.recent(6L, 1, 5))).isEmpty();
    }
    @Test void fiveAndSixRowsHaveAccurateNextPageWithoutOtherUsers() {
        rows(5, 6); rows(30, 7);
        assertThat(service.recent(6L, 0, 5)).containsEntry("hasNext", false);
        jdbc.update("INSERT INTO course_generation_jobs SELECT '6-005', user_id, status, course_id, error_code, start_date, end_date, created_at FROM course_generation_jobs WHERE id='6-000'");
        var first = service.recent(6L, 0, 5);
        assertThat(first).containsEntry("hasNext", true);
        assertThat(items(first)).extracting(row -> row.get("jobId")).containsExactly("6-005", "6-004", "6-003", "6-002", "6-001");
        assertThat(items(first).get(0)).containsEntry("createdAt", "2026-09-19T23:50Z");
        assertThat(items(service.recent(6L, 1, 5))).extracting(row -> row.get("jobId")).containsExactly("6-000");
    }
    @Test void queriesBeyondOldTwentyLimitAndPreservesDefaultSize() {
        rows(27, 6);
        assertThat(items(service.recent(6L))).hasSize(20);
        assertThat(items(service.recent(6L, 4, 5))).hasSize(5);
        assertThat(items(service.recent(6L, 5, 5))).hasSize(2);
    }
    @Test void rejectsMissingIdentityAndInvalidPageBounds() {
        assertThatThrownBy(() -> service.recent(null, 0, 5)).isInstanceOf(ResponseStatusException.class);
        for (int[] value : new int[][]{{-1,5},{10001,5},{0,0},{0,21}})
            assertThatThrownBy(() -> service.recent(6L, value[0], value[1])).isInstanceOf(ResponseStatusException.class);
    }
}
