package com.example.hangat.map.detail;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.repository.PlaceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 소개글 청크 저장. 트랜잭션을 끊으려고 서비스와 분리한다 -
 * 사유는 {@link com.example.hangat.map.place.PlaceIngestWriter} 참고.
 */
@Component
public class OverviewIngestWriter {

    private final PlaceRepository placeRepository;

    public OverviewIngestWriter(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    /** 한 건. overview 가 null 이면 KTO가 소개글을 안 준 것이다. */
    public record Row(Long placeId, String overview) {
    }

    /** 저장 결과. 값이 없던 건수를 따로 세어 재시도 낭비를 파악한다. */
    public record ChunkResult(int updated, int empty) {
    }

    @Transactional
    public ChunkResult saveChunk(List<Row> rows) {
        int updated = 0;
        int empty = 0;
        for (Row row : rows) {
            Place place = placeRepository.findById(row.placeId()).orElse(null);
            if (place == null) {
                continue;
            }
            if (row.overview() == null) {
                empty++;   // KTO가 소개글을 안 준 곳. findTouristWithoutOverview 가 다음 배치에서 또 집는다
                continue;
            }
            place.updateOverview(row.overview());
            updated++;
        }
        return new ChunkResult(updated, empty);
    }
}
