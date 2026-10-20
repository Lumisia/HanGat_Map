package com.example.hangat.course;

import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.repository.AsyncCourseJobRepository;
import com.example.hangat.notification.service.inbox.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncCourseWithdrawalTest {
    JdbcTemplate jdbc;
    AsyncCourseService service;
    CourseService courses = mock(CourseService.class);
    NotificationService notifications = mock(NotificationService.class);

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE course_generation_jobs(id VARCHAR(40) PRIMARY KEY, user_id BIGINT)");
        service = new AsyncCourseService(new AsyncCourseJobRepository(jdbc), new ObjectMapper(), courses,
                mock(CourseAccommodationService.class), mock(CourseClaimTokenService.class), notifications,
                mock(EntityManager.class), new DataSourceTransactionManager(source));
    }

    @Test void completedExternalWorkCannotPersistAfterWithdrawal() throws Exception {
        jdbc.update("INSERT INTO users VALUES(1,'WITHDRAWN')");
        var ticket = ticket();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "finish", ticket, null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(courses, notifications);
    }

    @Test void completedExternalWorkCannotRecreatePurgedOwner() throws Exception {
        var ticket = ticket();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "finish", ticket, null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(courses, notifications);
    }

    @Test void failureCallbackAfterPurgeIsHarmless() {
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "fail", "purged-job", "lease", "AI_GENERATION_FAILED"))
                .doesNotThrowAnyException();
        verifyNoInteractions(notifications);
    }

    private Object ticket() throws Exception {
        var type = Class.forName(AsyncCourseService.class.getName() + "$Ticket");
        var constructor = type.getDeclaredConstructor(String.class, String.class, Long.class, CourseRequestDto.class);
        constructor.setAccessible(true);
        return constructor.newInstance("job", "lease", 1L, null);
    }
}
