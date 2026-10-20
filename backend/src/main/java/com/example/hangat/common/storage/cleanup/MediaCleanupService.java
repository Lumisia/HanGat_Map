package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.MinioFileStorage;
import com.example.hangat.common.storage.StoredImage;
import com.example.hangat.user.repository.UserRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.regex.Pattern;

/** 객체 한 개씩 처리한다. 회원 잠금은 리뷰 첨부·프로필 교체와 공유한다. */
public class MediaCleanupService {
    private static final Pattern KEY = Pattern.compile(
            "(?:reviews|profiles)/([1-9][0-9]{0,18})/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(?:jpg|png|webp)");
    public enum Outcome { IGNORED, PROTECTED, CANDIDATE, WAITING, WOULD_DELETE, DELETED }
    private final MediaCleanupRepository candidates;
    private final UserRepository users;
    private final MinioFileStorage storage;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MediaCleanupService(MediaCleanupRepository candidates, UserRepository users,
                               MinioFileStorage storage, PlatformTransactionManager manager, Clock clock) {
        this.candidates = candidates;
        this.users = users;
        this.storage = storage;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
    }

    public Outcome inspect(StoredImage image, boolean dryRun) {
        return inspect(image, dryRun, false);
    }

    public Outcome inspect(StoredImage image, boolean dryRun, boolean onlyDeletedOwners) {
        var matcher = KEY.matcher(image.key());
        if (!matcher.matches() || image.modifiedAt() == null || image.etag() == null) return Outcome.IGNORED;
        final long owner;
        try { owner = Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException e) { return Outcome.IGNORED; }
        return transaction.execute(status -> {
            // 알 수 없는 소유자는 보호하되, 영구 삭제 트랜잭션이 남긴 증거가 있으면 재시도한다.
            boolean ownerExists = users.findByIdForUpdate(owner).isPresent();
            if (onlyDeletedOwners && ownerExists) return Outcome.PROTECTED;
            if (!ownerExists && candidates.deletedOwnerCount(owner) == 0)
                return Outcome.PROTECTED;
            if (candidates.isReferenced(image.key())) {
                candidates.deleteById(image.key());
                return Outcome.PROTECTED;
            }
            Instant now = clock.instant();
            var candidate = candidates.findById(image.key()).orElse(null);
            if (candidate == null || !candidate.matches(image)) {
                candidates.save(new MediaCleanupCandidate(image, now));
                return Outcome.CANDIDATE;
            }
            if (!candidate.eligible(now) || image.modifiedAt().plus(Duration.ofHours(24)).isAfter(now)) {
                return Outcome.WAITING;
            }
            // 목록 조회 이후 객체가 교체됐으면 유예 시간을 다시 시작한다.
            StoredImage current = storage.describe(image.key());
            if (!candidate.matches(current)) {
                candidates.save(new MediaCleanupCandidate(current, now));
                return Outcome.WAITING;
            }
            if (dryRun) return Outcome.WOULD_DELETE;
            storage.delete(image.key());
            candidates.deleteById(image.key());
            return Outcome.DELETED;
        });
    }
}
