package com.example.hangat.map.detail;

import com.example.hangat.map.detail.model.PlaceIntroItem;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.repository.PlaceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 상세 정보 청크 저장. 트랜잭션을 끊으려고 서비스와 분리한다 -
 * 사유는 {@link com.example.hangat.map.place.PlaceIngestWriter} 참고.
 */
@Component
public class PlaceDetailIngestWriter {

    private final PlaceRepository placeRepository;
    private final DetailFieldMapper mapper;

    public PlaceDetailIngestWriter(PlaceRepository placeRepository, DetailFieldMapper mapper) {
        this.placeRepository = placeRepository;
        this.mapper = mapper;
    }

    /** 한 건. 장소와 응답을 짝지어 넘긴다. */
    public record Row(Long placeId, PlaceIntroItem intro) {
    }

    /** 저장 결과. 값이 하나도 없던 건수를 따로 세어 재시도 낭비를 파악한다. */
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
            PlaceIntroItem it = row.intro();

            String hours = mapper.operatingHours(it);
            String rest = mapper.restDay(it);
            String fee = mapper.useFee(it);

            place.updateDetail(hours, rest,
                    mapper.parkingAvailable(it), mapper.toiletAvailable(it), fee);

            if (hours == null && rest == null && fee == null) {
                // KTO가 세 값을 다 안 준 장소. findWithoutDetail 이 다음 배치에서 또 집는다
                empty++;
            } else {
                updated++;
            }
        }
        return new ChunkResult(updated, empty);
    }
}
