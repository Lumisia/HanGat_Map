package com.example.hangat.review;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.map.model.entity.*;
import com.example.hangat.review.model.*;
import com.example.hangat.review.repository.ReviewRepository;
import com.example.hangat.review.service.ReviewPhotoService;
import com.example.hangat.review.service.ReviewService;
import com.example.hangat.user.model.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(ReviewService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewWriteConcurrencyTest {
    private static final java.util.concurrent.atomic.AtomicInteger regionOrder = new java.util.concurrent.atomic.AtomicInteger();
    @Autowired ReviewService service;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean ReviewRepository reviews;
    @MockitoBean ReviewPhotoService photos;

    enum Operation { CREATE, UPDATE, DELETE }
    record Fixture(long user, long place, long review) {}

    @Test
    void stalePlaceInPersistenceContextDoesNotOverwriteLatestDetails() {
        Fixture fixture = fixture(UserStatus.ACTIVE);
        when(photos.validateAttachments(any(), any())).thenReturn(List.of());
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            em.find(Place.class, fixture.place());
            jdbc.update("UPDATE places SET phone='064-123-4567' WHERE id=?", fixture.place());
            write(Operation.CREATE, fixture);
        });
        assertThat(jdbc.queryForObject("SELECT phone FROM places WHERE id=?", String.class, fixture.place()))
                .isEqualTo("064-123-4567");
    }

    @Test
    void staleReviewInPersistenceContextCannotBeEditedAfterDeletion() {
        Fixture fixture = fixture(UserStatus.ACTIVE);
        when(photos.validateAttachments(any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            em.find(Review.class, fixture.review());
            jdbc.update("UPDATE reviews SET status='DELETED' WHERE id=?", fixture.review());
            write(Operation.UPDATE, fixture);
        })).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getStatus()).isEqualTo(BaseResponseStatus.REVIEW_NOT_FOUND));
    }

    @Test
    void staleUserInPersistenceContextCannotWriteAfterWithdrawal() {
        Fixture fixture = fixture(UserStatus.ACTIVE);
        when(photos.validateAttachments(any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            em.find(User.class, fixture.user());
            jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", fixture.user());
            write(Operation.CREATE, fixture);
        })).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getStatus()).isEqualTo(BaseResponseStatus.ACCOUNT_WITHDRAWN));
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void aggregationWaitsForThePlaceLockHeldByAccountCleanup(Operation operation) throws Exception {
        Fixture fixture = fixture(UserStatus.ACTIVE);
        when(photos.validateAttachments(any(), any())).thenReturn(List.of());
        CountDownLatch writerStarted = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM places WHERE id=? FOR UPDATE", Long.class, fixture.place());
                worker.submit(() -> {
                    try {
                        writerStarted.countDown();
                        write(operation, fixture);
                        done.complete(null);
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
                try {
                    assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    org.mockito.Mockito.verify(reviews, org.mockito.Mockito.after(800).never()).summarize(fixture.place());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            done.get(10, TimeUnit.SECONDS);
            org.mockito.Mockito.verify(reviews).summarize(fixture.place());
            int expectedCount = operation == Operation.CREATE ? 2 : operation == Operation.UPDATE ? 1 : 0;
            assertThat(jdbc.queryForObject("SELECT review_count FROM places WHERE id=?", Integer.class, fixture.place()))
                    .isEqualTo(expectedCount);
            if (operation != Operation.DELETE) {
                assertThat(jdbc.queryForObject("SELECT rating_avg FROM places WHERE id=?", java.math.BigDecimal.class, fixture.place()))
                        .isEqualByComparingTo(operation == Operation.CREATE ? "3.00" : "5.00");
            }
        } finally {
            worker.shutdown();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void withdrawnAccountCannotWriteAfterTheAuthenticationCheck(Operation operation) {
        Fixture fixture = fixture(UserStatus.WITHDRAWN);
        when(photos.validateAttachments(any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> write(operation, fixture)).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getStatus()).isEqualTo(BaseResponseStatus.ACCOUNT_WITHDRAWN));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE user_id=? AND status='ACTIVE'", Integer.class, fixture.user()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rating FROM reviews WHERE id=?", Integer.class, fixture.review()))
                .isEqualTo(1);
    }

    private void write(Operation operation, Fixture fixture) {
        ReviewCreateRequest request = new ReviewCreateRequest();
        ReflectionTestUtils.setField(request, "rating", (byte) 5);
        ReflectionTestUtils.setField(request, "imageUrls", List.of());
        switch (operation) {
            case CREATE -> service.create(fixture.place(), fixture.user(), request);
            case UPDATE -> service.update(fixture.review(), fixture.user(), request);
            case DELETE -> service.delete(fixture.review(), fixture.user());
        }
    }

    private Fixture fixture(UserStatus status) {
        return new TransactionTemplate(manager).execute(tx -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Region region = Region.builder().code(suffix).name("지역" + suffix).displayOrder((byte) regionOrder.incrementAndGet()).build();
            PlaceCategory category = PlaceCategory.builder().code(suffix).name("분류" + suffix).build();
            em.persist(region);
            em.persist(category);
            Place place = Place.builder().region(region).primaryCategory(category).name("장소").normalizedName("장소").build();
            User user = User.builder().email(suffix + "@test.local").nickname(suffix).status(status).build();
            em.persist(place);
            em.persist(user);
            Review review = Review.builder().place(place).userId(user.getId()).rating((byte) 1).status(ReviewStatus.ACTIVE).build();
            em.persist(review);
            return new Fixture(user.getId(), place.getId(), review.getId());
        });
    }
}
