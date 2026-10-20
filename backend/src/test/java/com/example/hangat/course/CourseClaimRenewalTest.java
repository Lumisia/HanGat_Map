package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseClaimRenewalTest {
    private static final String SECRET = "renewal-test-only-secret-012345678901234567890123456789";
    private final MutableClock clock = new MutableClock();
    private final CourseClaimTokenService tokens = new CourseClaimTokenService(SECRET, 1800000, clock);

    @Test void slidingWindowNeverMovesOriginalTwoHourBoundary() {
        var proof = tokens.issue(11L);
        assertThat(proof.expiresAt()).isEqualTo(clock.start.plusSeconds(1800));
        String first = proof.token();
        for (int minutes : new int[]{20, 40, 60, 80, 100, 110}) {
            clock.now = clock.start.plusSeconds(minutes * 60L);
            proof = tokens.renew(proof.token(), 11L);
            assertThat(proof.expiresAt()).isEqualTo(clock.start.plusSeconds(Math.min(minutes + 30, 120) * 60L));
            if (minutes == 20) tokens.validate(first, 11L); // renewal is not one-time revocation
        }
        String latest = proof.token();
        clock.now = clock.start.plusSeconds(7200);
        assertThatThrownBy(() -> tokens.renew(latest, 11L)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tokens.validate(latest, 11L)).isInstanceOf(BaseException.class);
    }

    @Test void exactExpiryAndMissingWrongOrTamperedProofCannotRenew() {
        String token = tokens.issue(11L).token();
        assertThatThrownBy(() -> tokens.renew(null, 11L)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tokens.renew(token, 12L)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tokens.renew(token + "x", 11L)).isInstanceOf(BaseException.class);
        clock.now = clock.start.plusSeconds(1800);
        assertThatThrownBy(() -> tokens.renew(token, 11L)).isInstanceOf(BaseException.class);
    }

    @Test void legacySignedIatIsPreservedAndMissingIatIsRejected() throws Exception {
        clock.now = clock.start.plusSeconds(110 * 60);
        var proof = tokens.renew(legacy(true), 11L);
        assertThat(proof.expiresAt()).isEqualTo(clock.start.plusSeconds(7200));
        assertThatThrownBy(() -> tokens.renew(legacy(false), 11L)).isInstanceOf(BaseException.class);
    }

    @ParameterizedTest @EnumSource(value = CourseStatus.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void nonReadyCourseCannotRenew(CourseStatus status) {
        assertRejected(Course.builder().id(11L).status(status).courseType(CourseType.USER).build());
    }
    @Test void ownedAndSampleCoursesCannotRenew() {
        assertRejected(Course.builder().id(11L).status(CourseStatus.READY).user(User.builder().id(7L).build()).build());
        assertRejected(Course.builder().id(11L).status(CourseStatus.READY).courseType(CourseType.SAMPLE).build());
    }
    @Test void readyCourseRenewsWithoutWritesAndMissingCourseFails() {
        CourseRepository repository = mock(CourseRepository.class);
        var course = Course.builder().id(11L).status(CourseStatus.READY).courseType(CourseType.USER).build();
        when(repository.findByIdForClaim(11L)).thenReturn(Optional.of(course));
        var service = new CourseClaimService(mock(UserRepository.class), repository, tokens);
        String token = tokens.issue(11L).token();
        clock.now = clock.start.plusSeconds(1200);
        assertThat(service.renew(11L, token).expiresAt()).isEqualTo(clock.start.plusSeconds(3000));
        assertThat(course.getStatus()).isEqualTo(CourseStatus.READY);
        verify(repository, never()).save(any());
        when(repository.findByIdForClaim(11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.renew(11L, token)).isInstanceOf(BaseException.class);
    }
    private void assertRejected(Course course) {
        CourseRepository repository = mock(CourseRepository.class);
        when(repository.findByIdForClaim(11L)).thenReturn(Optional.of(course));
        var service = new CourseClaimService(mock(UserRepository.class), repository, tokens);
        assertThatThrownBy(() -> service.renew(11L, tokens.issue(11L).token())).isInstanceOf(BaseException.class);
        verify(repository, never()).save(any());
    }
    private String legacy(boolean iat) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var key = Keys.hmacShaKeyFor(mac.doFinal("hangat/course-claim/v1".getBytes(StandardCharsets.UTF_8)));
        var builder = Jwts.builder().subject("11").claim("purpose", "COURSE_CLAIM").id("legacy")
                .expiration(Date.from(clock.start.plusSeconds(7200))).signWith(key);
        if (iat) builder.issuedAt(Date.from(clock.start));
        return builder.compact();
    }
    private static class MutableClock extends Clock {
        final Instant start = Instant.parse("2026-09-07T05:00:00Z");
        Instant now = start;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
