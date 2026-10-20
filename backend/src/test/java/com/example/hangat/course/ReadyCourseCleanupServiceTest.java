package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.AsyncCourseJobRepository;
import com.example.hangat.course.repository.CourseItemCostRepository;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.course.service.CourseQueryService;
import com.example.hangat.domain.congestion.CongestionService;
import com.example.hangat.user.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadyCourseCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T06:00:00Z");
    private final CourseRetentionPolicy retention =
            new CourseRetentionPolicy(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void twoHourBoundaryIsExpiredButOneNanosecondBeforeIsNot() {
        LocalDateTime boundary = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(2);
        assertThat(retention.isExpiredReady(ready(1L, null, boundary))).isTrue();
        assertThat(retention.isExpiredReady(ready(2L, null, boundary.plusNanos(1)))).isFalse();
        assertThatThrownBy(() -> retention.requireAvailable(ready(3L, null, boundary)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(BaseResponseStatus.COURSE_EXPIRED));
    }

    @Test
    void expiresAnonymousAndMemberReadyCoursesAndRemovesOnlyOwnedChildrenAndTerminalJob() {
        CourseRepository courses = mock(CourseRepository.class);
        CourseItemRepository items = mock(CourseItemRepository.class);
        CourseItemCostRepository costs = mock(CourseItemCostRepository.class);
        AsyncCourseJobRepository jobs = mock(AsyncCourseJobRepository.class);
        LocalDateTime old = retention.cutoff().minusSeconds(1);
        Course anonymous = ready(11L, null, old);
        Course member = ready(12L, User.builder().id(7L).build(), old);
        when(courses.findExpiredReadyIds(any(), any(Pageable.class))).thenReturn(List.of(11L, 12L));
        when(courses.findByIdForClaim(11L)).thenReturn(Optional.of(anonymous));
        when(courses.findByIdForClaim(12L)).thenReturn(Optional.of(member));

        int count = service(courses, items, costs, jobs).cleanupBatch();

        assertThat(count).isEqualTo(2);
        verify(costs).deleteByCourse(11L); verify(costs).deleteByCourse(12L);
        verify(items).deleteByCourse(11L); verify(items).deleteByCourse(12L);
        verify(jobs).deleteTerminalArtifactsForCourse(11L);
        verify(jobs).deleteTerminalArtifactsForCourse(12L);
        verify(courses).delete(anonymous);
        verify(courses).delete(member);
        var deletionOrder = inOrder(costs, items, jobs, courses);
        deletionOrder.verify(costs).deleteByCourse(11L);
        deletionOrder.verify(items).deleteByCourse(11L);
        deletionOrder.verify(jobs).deleteTerminalArtifactsForCourse(11L);
        deletionOrder.verify(courses).delete(anonymous);
        deletionOrder.verify(costs).deleteByCourse(12L);
        deletionOrder.verify(items).deleteByCourse(12L);
        deletionOrder.verify(jobs).deleteTerminalArtifactsForCourse(12L);
        deletionOrder.verify(courses).delete(member);
    }

    @Test
    void savedSampleAndActiveJobAreProtectedAndSaveRaceIsRecheckedUnderLock() {
        CourseRepository courses = mock(CourseRepository.class);
        CourseItemRepository items = mock(CourseItemRepository.class);
        CourseItemCostRepository costs = mock(CourseItemCostRepository.class);
        AsyncCourseJobRepository jobs = mock(AsyncCourseJobRepository.class);
        LocalDateTime old = retention.cutoff().minusSeconds(1);
        Course savedAfterCandidateRead = ready(21L, null, old);
        savedAfterCandidateRead.markSaved(User.builder().id(8L).build(), "saved");
        Course sample = Course.builder().id(22L).courseType(CourseType.SAMPLE)
                .status(CourseStatus.READY).createdAt(old).build();
        Course active = ready(23L, null, old);
        when(courses.findExpiredReadyIds(any(), any(Pageable.class))).thenReturn(List.of(21L, 22L, 23L));
        when(courses.findByIdForClaim(21L)).thenReturn(Optional.of(savedAfterCandidateRead));
        when(courses.findByIdForClaim(22L)).thenReturn(Optional.of(sample));
        when(courses.findByIdForClaim(23L)).thenReturn(Optional.of(active));
        when(jobs.hasActiveForCourse(23L)).thenReturn(true);

        assertThat(service(courses, items, costs, jobs).cleanupBatch()).isZero();
        assertThat(savedAfterCandidateRead.getStatus()).isEqualTo(CourseStatus.SAVED);
        assertThat(sample.getStatus()).isEqualTo(CourseStatus.READY);
        assertThat(active.getStatus()).isEqualTo(CourseStatus.READY);
        verify(items, never()).deleteByCourse(any());
        verify(costs, never()).deleteByCourse(any());
        verify(jobs, never()).deleteTerminalArtifactsForCourse(any());
        verify(courses, never()).delete(any(Course.class));
    }

    @Test
    void claimAndRenewReturnExpiredContractWithoutMintingOrSaving() {
        CourseRepository courses = mock(CourseRepository.class);
        CourseClaimTokenService tokens = mock(CourseClaimTokenService.class);
        var users = mock(com.example.hangat.user.repository.UserRepository.class);
        Course expired = ready(31L, null, retention.cutoff());
        when(courses.findByIdForClaim(31L)).thenReturn(Optional.of(expired));
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        CourseClaimService claims = new CourseClaimService(users, courses, tokens, retention);

        assertThatThrownBy(() -> claims.claim(31L, 7L,
                new com.example.hangat.course.model.CourseClaimRequest("proof", "title")))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(BaseResponseStatus.COURSE_EXPIRED));
        assertThatThrownBy(() -> claims.renew(31L, "proof"))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(BaseResponseStatus.COURSE_EXPIRED));
        verify(tokens, never()).renew(any(), any());
    }

    @Test
    void detailReturnsExpiredContractBeforeLoadingItinerary() {
        CourseRepository courses = mock(CourseRepository.class);
        CourseItemRepository items = mock(CourseItemRepository.class);
        Course expired = ready(41L, null, retention.cutoff());
        when(courses.findByIdWithAccommodation(41L)).thenReturn(Optional.of(expired));
        CourseQueryService queries = new CourseQueryService(
                courses,
                items,
                mock(CongestionService.class),
                mock(DbCourseWeatherFactsProvider.class),
                retention, mock(com.example.hangat.course.CourseBudgetService.class));

        assertThatThrownBy(() -> queries.detail(41L, null))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(BaseResponseStatus.COURSE_EXPIRED));
        verify(items, never()).findItemsWithPlace(any());
    }

    private ReadyCourseCleanupService service(CourseRepository courses, CourseItemRepository items,
            CourseItemCostRepository costs, AsyncCourseJobRepository jobs) {
        return new ReadyCourseCleanupService(courses, items, costs, jobs, retention);
    }

    private Course ready(Long id, User user, LocalDateTime createdAt) {
        return Course.builder().id(id).user(user).courseType(CourseType.USER)
                .status(CourseStatus.READY).createdAt(createdAt).build();
    }
}
