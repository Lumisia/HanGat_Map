package com.example.hangat.map.repository;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PlaceImageRepository extends JpaRepository<PlaceImage, Long> {

    List<PlaceImage> findByPlaceIdOrderBySortOrder(Long placeId);

    /**
     * 장소별 첫 사진(정렬값 최솟값) 한 장씩 - 찜 목록(favorite)의 대표사진. (placeId, thumbnailUrl, imageUrl).
     * 사진 없는 장소는 행이 없다.
     */
    @Query("""
            select i.place.id, i.thumbnailUrl, i.imageUrl
            from PlaceImage i
            where i.place.id in :placeIds
              and i.sortOrder = (select min(j.sortOrder) from PlaceImage j where j.place = i.place)
            """)
    List<Object[]> findFirstImageOf(@Param("placeIds") Collection<Long> placeIds);

    /** 재적재용 선삭제 - 일반 delete 는 INSERT 가 먼저 나가 UK 충돌이 난다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlaceImage i where i.place = :place")
    void deleteByPlace(@Param("place") Place place);
}
