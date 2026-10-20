package com.example.hangat.course.share;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.course.service.CourseCommandService;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CourseShareConcurrencyTest {
    @Autowired CourseShareService sharing;
    @Autowired CourseCommandService commands;
    @Autowired CourseRepository courses;
    @Autowired CourseShareRepository shares;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;

    private Course seed() {
        return new TransactionTemplate(transactions).execute(status -> {
            User owner = users.save(User.signUpWithSocial(UUID.randomUUID() + "@share.local", "동시 공유"));
            Course course = courses.save(Course.builder().startDate(LocalDate.of(2026, 10, 1))
                    .endDate(LocalDate.of(2026, 10, 1)).transport(Transport.RENTAL_CAR).build());
            course.markReady(); course.markSaved(owner, "동시 생성 검증");
            return course;
        });
    }
    private void cleanup(Course course) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            shares.deleteById(course.getId()); courses.deleteById(course.getId()); users.deleteById(course.getUser().getId());
        });
    }

    @Test void concurrentFirstCreatesReturnOneToken() throws Exception {
        Course course = seed();
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                start.await(); return sharing.create(course.getId(), course.getUser().getId()).token();
            })).toList();
            start.countDown();
            var tokens = new java.util.HashSet<String>();
            for (var future : futures) tokens.add(future.get(15, TimeUnit.SECONDS));
            assertThat(tokens).hasSize(1);
            assertThat(sharing.status(course.getId(), course.getUser().getId()).token()).isEqualTo(tokens.iterator().next());
        } finally { pool.shutdownNow(); pool.awaitTermination(15, TimeUnit.SECONDS); cleanup(course); }
    }

    @Test void concurrentDeleteNeverLeavesAPublicCourse() throws Exception {
        Course course = seed();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var create = pool.submit(() -> {
                start.await();
                try { return sharing.create(course.getId(), course.getUser().getId()).token(); }
                catch (BaseException expectedIfDeletedFirst) { return null; }
            });
            var delete = pool.submit(() -> { start.await(); commands.delete(course.getId(), course.getUser().getId()); return true; });
            start.countDown();
            String token = create.get(15, TimeUnit.SECONDS);
            assertThat(delete.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(courses.findById(course.getId()).orElseThrow().getStatus()).isEqualTo(CourseStatus.DELETED);
            if (token != null) assertThatThrownBy(() -> sharing.read(token)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> sharing.create(course.getId(), course.getUser().getId())).isInstanceOf(BaseException.class);
        } finally { pool.shutdownNow(); pool.awaitTermination(15, TimeUnit.SECONDS); cleanup(course); }
    }

    @Test void revokeRacingWithCreateStillAllowsDefinitiveRevocation() throws Exception {
        Course course = seed();
        String original = sharing.create(course.getId(), course.getUser().getId()).token();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var create = pool.submit(() -> { start.await(); return sharing.create(course.getId(), course.getUser().getId()).token(); });
            var revoke = pool.submit(() -> { start.await(); return sharing.revoke(course.getId(), course.getUser().getId()); });
            start.countDown();
            String returned = create.get(15, TimeUnit.SECONDS);
            revoke.get(15, TimeUnit.SECONDS);
            // 두 실행 순서 모두 허용하지만, 중지된 원래 링크가 부활하면 안 된다.
            assertThatThrownBy(() -> sharing.read(original)).isInstanceOf(BaseException.class);
            var afterRace = sharing.status(course.getId(), course.getUser().getId());
            if (afterRace.active()) {
                assertThat(returned).isNotEqualTo(original).isEqualTo(afterRace.token());
                assertThat(sharing.read(returned).title()).isEqualTo("동시 생성 검증");
            } else {
                assertThatThrownBy(() -> sharing.read(returned)).isInstanceOf(BaseException.class);
            }
            sharing.revoke(course.getId(), course.getUser().getId());
            assertThat(sharing.status(course.getId(), course.getUser().getId()).active()).isFalse();
            assertThatThrownBy(() -> sharing.read(original)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> sharing.read(returned)).isInstanceOf(BaseException.class);
        } finally { pool.shutdownNow(); pool.awaitTermination(15, TimeUnit.SECONDS); cleanup(course); }
    }
}
