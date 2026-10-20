package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.StoredImage;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import com.example.hangat.map.model.entity.*;
import com.example.hangat.review.model.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaCleanupRepositoryTest {
    @Autowired MediaCleanupRepository repository;
    @Autowired UserRepository users;
    @Autowired EntityManager em;

    @Test void currentProfileIsProtectedAndReplacementReleasesOldReference() {
        var user = users.saveAndFlush(User.signUpWithSocial(UUID.randomUUID()+"@test.local", UUID.randomUUID().toString()));
        String key = "profiles/"+user.getId()+"/12345678-1234-1234-1234-123456789abc.jpg";
        assertThat(repository.isReferenced(key)).isFalse();
        user.updateProfileImage(key);
        em.flush();
        assertThat(repository.isReferenced(key)).isTrue();
        user.updateProfileImage(key+"-new");
        em.flush();
        assertThat(repository.isReferenced(key)).isFalse();
    }

    @Test void candidateSurvivesReloadWithUtcGraceBoundary() {
        var image = new StoredImage("reviews/1/a.jpg", Instant.parse("2026-09-12T00:00:00.123456789Z"), "etag");
        repository.saveAndFlush(new MediaCleanupCandidate(image, Instant.parse("2026-09-14T12:00:00Z")));
        em.clear();
        var saved = repository.findById(image.key()).orElseThrow();
        assertThat(saved.matches(image)).isTrue();
        assertThat(saved.eligible(Instant.parse("2026-09-15T11:59:59Z"))).isFalse();
        assertThat(saved.eligible(Instant.parse("2026-09-15T12:00:00Z"))).isTrue();
    }

    @Test void activeReviewProtectsFileButDeletedReviewDoesNot() {
        var place = place();
        var review = Review.builder().place(place).userId(1L).status(ReviewStatus.ACTIVE).build();
        em.persist(review);
        String key = "reviews/1/12345678-1234-1234-1234-123456789abc.jpg";
        em.persist(ReviewImage.builder().review(review).storageKey(key).imageUrl("/media/"+key).sortOrder(0).build());
        em.flush();
        assertThat(repository.isReferenced(key)).isTrue();
        review.delete();
        em.flush();
        assertThat(repository.isReferenced(key)).isFalse();
    }

    @Test void placeImageAndThumbnailReferencesAreProtected() {
        var place = place();
        String key = "reviews/1/12345678-1234-1234-1234-123456789abc.jpg";
        em.persist(PlaceImage.builder().place(place).imageUrl("https://example.test/photo.jpg")
                .thumbnailUrl("https://example.test/media/"+key).urlHash("a".repeat(64))
                .sortOrder(0).isPrimary(true).build());
        em.flush();
        assertThat(repository.isReferenced(key)).isTrue();
    }

    private Place place() {
        var region = em.createQuery("select r from Region r", Region.class).setMaxResults(1).getSingleResult();
        var category = em.createQuery("select c from PlaceCategory c", PlaceCategory.class).setMaxResults(1).getSingleResult();
        var place = Place.builder().name("정리 테스트").normalizedName("정리테스트")
                .region(region).primaryCategory(category).build();
        em.persist(place);
        return place;
    }
}
