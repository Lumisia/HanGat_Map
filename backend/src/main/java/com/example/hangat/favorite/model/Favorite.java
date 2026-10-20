package com.example.hangat.favorite.model;

import com.example.hangat.map.model.entity.Place;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 찜(명세서 13.0 favorite) - MAP_009 지도 하트와 MY_006 마이페이지 목록이 같은 행을 본다.
 *
 * <p>한 회원이 같은 장소를 두 번 찜하는 행은 없다({@code uk_favorites_user_place}).
 * 토글은 "있으면 지우고 없으면 넣는다"가 아니라 <b>PUT=있게, DELETE=없게</b>로 멱등하게 다룬다 -
 * 두 번 눌러도, 두 탭에서 동시에 눌러도 결과가 같다.
 *
 * <p>작성자는 {@link com.example.hangat.review.model.Review}와 같은 이유로 ID만 둔다 -
 * 목록 응답에 회원 정보가 필요 없고, 사용자 엔티티를 끌어오지 않는다.
 */
@Entity
@Table(
        name = "favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_favorites_user_place", columnNames = {"user_id", "place_id"})
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 찜한 회원. JWT 인증 정보의 Long ID 그대로다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Place place;

    /** 찜한 시각 - "최근 찜한 순" 정렬 기준. 갱신되지 않는다(다시 찜해도 행이 그대로라 시각도 그대로). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
