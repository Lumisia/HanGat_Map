package com.example.hangat.map.repository;

import com.example.hangat.map.model.entity.PlaceSourceMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 장소 ↔ 출처 매핑 조회.
 *
 * <p>적재 배치가 "이 장소를 전에 받은 적 있나"를 판단하는 통로다.
 * 이름이 아니라 <b>출처 쪽 ID</b>로 찾는다 - 같은 이름의 다른 장소가 실제로 있고,
 * KTO가 이름을 바꿔도 {@code contentid}는 그대로이기 때문이다.
 */
public interface PlaceSourceMappingRepository extends JpaRepository<PlaceSourceMapping, Long> {

    Optional<PlaceSourceMapping> findBySourceCodeAndSourcePlaceId(String sourceCode, String sourcePlaceId);

    /** 상세 배치용 - 장소에서 KTO contentId 를 거꾸로 찾는다. */
    Optional<PlaceSourceMapping> findByPlaceIdAndSourceCode(Long placeId, String sourceCode);

    /**
     * 한 출처의 매핑을 한 번에 읽는다. 2,147건을 하나씩 조회하면 쿼리가 2,147번 나가므로,
     * 배치 시작 시 이 메서드로 전부 읽어 메모리에서 대조한다.
     * {@code place}를 함께 로드해 저장 단계에서 지연 로딩이 터지지 않게 한다.
     */
    @Query("select m from PlaceSourceMapping m join fetch m.place where m.source.code = :sourceCode")
    List<PlaceSourceMapping> findAllBySourceCodeWithPlace(@Param("sourceCode") String sourceCode);

    /** 출석 체크용 (매핑 id, 출처 ID, 활성 여부, 갱신 시각). 원문 payload(TEXT)까지 읽지 않으려고 엔티티 대신 컬럼만 뽑는다. */
    @Query("select m.id, m.sourcePlaceId, m.isActive, m.updatedAt from PlaceSourceMapping m where m.source.code = :sourceCode")
    List<Object[]> findPresenceRows(@Param("sourceCode") String sourceCode);

    @Query("select m from PlaceSourceMapping m join fetch m.place where m.id in :ids")
    List<PlaceSourceMapping> findAllWithPlaceByIdIn(@Param("ids") Collection<Long> ids);

    /** 다른 출처가 아직 보고 있는 장소 - 한 출처에서 사라졌다고 폐업으로 단정하지 않기 위해 */
    @Query("""
            select distinct m.place.id from PlaceSourceMapping m
            where m.place.id in :placeIds and m.isActive = true and m.source.code in :sourceCodes
            """)
    List<Long> findPlaceIdsStillSeenBy(@Param("placeIds") Collection<Long> placeIds,
                                       @Param("sourceCodes") Collection<String> sourceCodes);
}
