package com.example.hangat.notification.repository.inbox;

import com.example.hangat.notification.model.entity.NotificationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 알림함 조회와 읽음 처리를 담당하는 JPA 저장소. */
@Transactional(readOnly = true)
public interface NotificationRepository extends Repository<NotificationEntity, Long> {

    /** 엔티티 캐시 대신 값만 조회하여 같은 트랜잭션의 일괄 읽음 처리 결과도 반영한다. */
    @Query("""
            select n.id as id, n.type as type, n.title as title, n.message as message,
                   n.targetType as targetType, n.targetId as targetId,
                   n.createdAt as createdAt, n.readAt as readAt
            from NotificationEntity n
            where n.userId = :userId and n.id < :before and n.deletedAt is null
              and n.headerHiddenAt is null
            order by n.id desc
            """)
    List<NotificationView> findPage(
            @Param("userId") Long userId,
            @Param("before") Long before,
            Pageable pageable
    );

    /** 해당 사용자의 아직 읽지 않은 알림 수를 조회한다. */
    @Query("select count(n) from NotificationEntity n where n.userId = :userId and n.readAt is null and n.deletedAt is null")
    long countUnread(@Param("userId") Long userId);

    /** 본인 알림에만 최초 읽음 시각을 기록한다. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update NotificationEntity n set n.readAt = :readAt
            where n.id = :notificationId and n.userId = :userId and n.readAt is null and n.deletedAt is null
            """)
    int markRead(
            @Param("userId") Long userId,
            @Param("notificationId") Long notificationId,
            @Param("readAt") LocalDateTime readAt
    );

    /** 기존 읽음 시각을 보존하며 본인의 미확인 알림만 읽음으로 변경한다. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            update NotificationEntity n set n.readAt = :readAt
            where n.userId = :userId and n.readAt is null and n.deletedAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);

    /** 필터는 페이지를 자르기 전에 DB 전체 알림에 적용한다. */
    @Query("""
            select n.id as id, n.type as type, n.title as title, n.message as message,
                   n.targetType as targetType, n.targetId as targetId,
                   n.createdAt as createdAt, n.readAt as readAt
            from NotificationEntity n
            where n.userId = :userId and n.deletedAt is null
              and (:allTypes = true or (:excludeTypes = false and n.type in :types)
                   or (:excludeTypes = true and n.type not in :types))
            order by n.id desc
            """)
    Page<NotificationView> findInbox(@Param("userId") Long userId, @Param("allTypes") boolean allTypes,
                                    @Param("excludeTypes") boolean excludeTypes,
                                    @Param("types") List<String> types, Pageable pageable);

    /** 삭제는 본인의 표시 상태만 바꾼다. 재시도해도 중복 생성 방지 기록은 남는다. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update NotificationEntity n set n.deletedAt = :at where n.userId = :userId and n.id = :id and n.deletedAt is null")
    int hide(@Param("userId") Long userId, @Param("id") Long id, @Param("at") LocalDateTime at);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update NotificationEntity n set n.deletedAt = :at where n.userId = :userId and n.deletedAt is null")
    int hideAll(@Param("userId") Long userId, @Param("at") LocalDateTime at);

    /** 새로 도착한 미확인 알림은 남기고, 읽은 알림만 헤더에서 숨긴다. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update NotificationEntity n set n.headerHiddenAt = :at where n.userId = :userId and n.readAt is not null and n.deletedAt is null and n.headerHiddenAt is null")
    int hideHeader(@Param("userId") Long userId, @Param("at") LocalDateTime at);

    /** API 변환에 필요한 필드만 담는 조회 결과. */
    interface NotificationView {
        Long getId();
        String getType();
        String getTitle();
        String getMessage();
        String getTargetType();
        String getTargetId();
        LocalDateTime getCreatedAt();
        LocalDateTime getReadAt();
    }
}
