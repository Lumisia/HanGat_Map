package com.example.hangat.favorite.repository;

import com.example.hangat.favorite.model.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 찜 조회·삭제. 조회는 전부 회원 ID로 좁힌다 - 남의 찜이 섞일 경로를 만들지 않는다. */
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    boolean existsByUserIdAndPlaceId(Long userId, Long placeId);

    /** 지도 하트 상태용 - 장소 ID만. 목록 응답을 통째로 받는 것보다 훨씬 가볍다. */
    @Query("select f.place.id from Favorite f where f.userId = :userId order by f.createdAt desc")
    List<Long> findPlaceIdsByUserId(@Param("userId") Long userId);

    /**
     * 마이페이지 목록 - 장소와 권역·카테고리를 한 쿼리로 읽는다(to-one fetch join이라 행이 늘지 않는다).
     * 세부분류·대표사진·오늘 혼잡은 서비스가 장소 ID 묶음으로 따로 읽어 붙인다(N+1 회피).
     */
    @Query("""
            select f from Favorite f
              join fetch f.place p
              join fetch p.region
              join fetch p.primaryCategory
            where f.userId = :userId
            order by f.createdAt desc
            """)
    List<Favorite> findAllWithPlaceByUserId(@Param("userId") Long userId);

    /** 해제. 없는 행을 지워도 0을 돌려줄 뿐 실패가 아니다 - DELETE 는 멱등. */
    @Modifying
    @Query("delete from Favorite f where f.userId = :userId and f.place.id = :placeId")
    int deleteByUserIdAndPlaceId(@Param("userId") Long userId, @Param("placeId") Long placeId);
}
