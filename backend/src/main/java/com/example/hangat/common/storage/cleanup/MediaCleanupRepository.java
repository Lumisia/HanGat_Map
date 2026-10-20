package com.example.hangat.common.storage.cleanup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaCleanupRepository extends JpaRepository<MediaCleanupCandidate, String> {
    @Query(value = "SELECT COUNT(*) FROM deleted_media_owners WHERE user_id=:owner", nativeQuery = true)
    int deletedOwnerCount(@Param("owner") long owner);
    /** 삭제된 후기만 참조하는 파일은 정리 가능. 회원·장소 참조는 상태와 무관하게 보호한다. */
    @Query(value = """
            SELECT CASE WHEN
              EXISTS (SELECT 1 FROM review_images i JOIN reviews r ON r.id=i.review_id
                      WHERE i.storage_key=:key AND r.status <> 'DELETED')
              OR EXISTS (SELECT 1 FROM users u WHERE u.profile_image_key=:key)
              OR EXISTS (SELECT 1 FROM places p WHERE p.image_url LIKE CONCAT('%',:key,'%'))
              OR EXISTS (SELECT 1 FROM place_images p WHERE p.image_url LIKE CONCAT('%',:key,'%')
                          OR p.thumbnail_url LIKE CONCAT('%',:key,'%'))
            THEN 1 ELSE 0 END
            """, nativeQuery = true)
    int referenceCount(@Param("key") String key);

    default boolean isReferenced(String key) { return referenceCount(key) != 0; }
}
