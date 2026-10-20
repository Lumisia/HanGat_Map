package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.MinioFileStorage;
import com.example.hangat.common.storage.StoredImage;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaCleanupServiceTest {
    final String key = "reviews/1/12345678-1234-1234-1234-123456789abc.jpg";
    final Instant now = Instant.parse("2026-09-15T12:00:00Z");
    final StoredImage image = new StoredImage(key, now.minus(Duration.ofDays(3)), "etag");
    final MediaCleanupRepository candidates = mock(MediaCleanupRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final MinioFileStorage storage = mock(MinioFileStorage.class);
    MediaCleanupService service;

    @BeforeEach void setup() {
        var manager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) {}
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        service = new MediaCleanupService(candidates, users, storage, manager,
                Clock.fixed(now, ZoneOffset.UTC));
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(User.class)));
        when(candidates.findById(key)).thenReturn(Optional.empty());
        when(storage.describe(key)).thenReturn(image);
    }

    @Test void firstObservationNeverDeletesEvenOldUpload() {
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.CANDIDATE);
        verify(storage, never()).delete(anyString());
        verify(candidates).save(any(MediaCleanupCandidate.class));
    }

    @Test void referencedPhotoClearsCandidateAndIsNeverDeleted() {
        when(candidates.isReferenced(key)).thenReturn(true);
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.PROTECTED);
        verify(candidates).deleteById(key);
        verify(storage, never()).delete(anyString());
    }

    @Test void gracePeriodDoesNotExpireOneSecondEarly() {
        candidate(now.minusSeconds(86399));
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.WAITING);
        verify(storage, never()).delete(anyString());
    }

    @Test void dryRunNeverDeletesEligibleFile() {
        candidate(now.minusSeconds(86400));
        assertThat(service.inspect(image, true)).isEqualTo(MediaCleanupService.Outcome.WOULD_DELETE);
        verify(storage, never()).delete(anyString());
    }

    @Test void deletesOnlyAfterGraceAndCurrentMetadataCheck() {
        candidate(now.minusSeconds(86400));
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.DELETED);
        verify(storage).describe(key);
        verify(storage).delete(key);
        verify(candidates).deleteById(key);
    }

    @Test void changedObjectIsNotDeleted() {
        candidate(now.minusSeconds(86400));
        when(storage.describe(key)).thenReturn(new StoredImage(key, now, "replacement"));
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.WAITING);
        verify(storage, never()).delete(anyString());
    }

    @Test void unknownPathAndMissingOwnerAreProtected() {
        assertThat(service.inspect(new StoredImage("places/1/photo.jpg", image.modifiedAt(), "etag"), false))
                .isEqualTo(MediaCleanupService.Outcome.IGNORED);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.PROTECTED);
        verify(storage, never()).delete(anyString());
    }

    @Test void databaseFailureDoesNotDelete() {
        when(candidates.isReferenced(key)).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(() -> service.inspect(image, false)).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).delete(anyString());
    }

    @Test void committedDeletedOwnerCanBeRetriedAndDeletedAfterGrace() {
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        when(candidates.deletedOwnerCount(1L)).thenReturn(1);
        candidate(now.minusSeconds(86400));
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.DELETED);
        verify(storage).delete(key);
    }

    @Test void tombstoneDoesNotOverridePublicPlaceReference() {
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        when(candidates.deletedOwnerCount(1L)).thenReturn(1);
        when(candidates.isReferenced(key)).thenReturn(true);
        assertThat(service.inspect(image, false)).isEqualTo(MediaCleanupService.Outcome.PROTECTED);
        verify(storage, never()).delete(anyString());
    }

    @Test void storageFailureKeepsCandidateForRetry() {
        candidate(now.minusSeconds(86400));
        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(key);
        assertThatThrownBy(() -> service.inspect(image, false)).isInstanceOf(IllegalStateException.class);
        verify(candidates, never()).deleteById(key);
    }

    @Test void accountOnlyJobNeverDeletesExistingOwnersOrUnknownMissingOwners() {
        candidate(now.minusSeconds(86400));
        assertThat(service.inspect(image, false, true)).isEqualTo(MediaCleanupService.Outcome.PROTECTED);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.inspect(image, false, true)).isEqualTo(MediaCleanupService.Outcome.PROTECTED);
        verify(storage, never()).delete(anyString());
        when(candidates.deletedOwnerCount(1L)).thenReturn(1);
        assertThat(service.inspect(image, false, true)).isEqualTo(MediaCleanupService.Outcome.DELETED);
        verify(storage).delete(key);
    }

    @Test void profileUsesSameGracePeriod() {
        String profile = key.replace("reviews/", "profiles/");
        when(candidates.findById(profile)).thenReturn(Optional.empty());
        assertThat(service.inspect(new StoredImage(profile, image.modifiedAt(), "etag"), false))
                .isEqualTo(MediaCleanupService.Outcome.CANDIDATE);
        verify(storage, never()).delete(anyString());
    }

    private void candidate(Instant firstSeen) {
        when(candidates.findById(key)).thenReturn(Optional.of(new MediaCleanupCandidate(image, firstSeen)));
    }
}
