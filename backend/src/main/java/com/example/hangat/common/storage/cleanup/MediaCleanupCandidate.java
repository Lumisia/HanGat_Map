package com.example.hangat.common.storage.cleanup;

import com.example.hangat.common.storage.StoredImage;
import jakarta.persistence.*;
import java.time.*;
import java.util.Objects;

@Entity
@Table(name = "media_cleanup_candidates")
public class MediaCleanupCandidate {
    @Id @Column(name = "storage_key", length = 200)
    private String key;
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;
    @Column(name = "object_modified_at", nullable = false)
    private LocalDateTime modifiedAt;
    @Column(name = "object_etag", length = 200, nullable = false)
    private String etag;

    protected MediaCleanupCandidate() {}

    public MediaCleanupCandidate(StoredImage image, Instant now) {
        key = image.key();
        firstSeenAt = databaseTime(now);
        modifiedAt = databaseTime(image.modifiedAt());
        etag = image.etag();
    }

    public boolean matches(StoredImage image) {
        return Objects.equals(etag, image.etag())
                && modifiedAt.equals(databaseTime(image.modifiedAt()));
    }

    // DATETIME(6)와 정밀도를 맞춰 DB 재조회 때 같은 객체를 교체된 객체로 오인하지 않는다.
    private static LocalDateTime databaseTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public boolean eligible(Instant now) {
        return !firstSeenAt.toInstant(ZoneOffset.UTC).plus(Duration.ofHours(24)).isAfter(now);
    }
}
