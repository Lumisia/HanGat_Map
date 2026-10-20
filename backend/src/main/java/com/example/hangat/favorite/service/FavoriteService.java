package com.example.hangat.favorite.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.favorite.model.Favorite;
import com.example.hangat.favorite.model.FavoriteResponse;
import com.example.hangat.favorite.model.FavoriteStateResponse;
import com.example.hangat.favorite.repository.FavoriteRepository;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.PlaceImageRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 찜 - 지도 하트(MAP_009)와 마이페이지 찜 목록(MY_006·007)의 단일 진실.
 *
 * <p>찜/해제는 <b>멱등</b>이다: PUT 은 "찜된 상태로 만든다", DELETE 는 "없는 상태로 만든다".
 * 화면이 눌린 순서와 서버 도착 순서가 어긋나도(빠른 연타, 두 탭) 마지막 요청의 의도대로 끝난다.
 */
@Service
@Transactional(readOnly = true)
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final PlaceRepository placeRepository;
    private final PlaceImageRepository placeImageRepository;
    private final CongestionForecastRepository forecastRepository;

    /** 팀이 허용한 Lombok에 @RequiredArgsConstructor가 없어 직접 선언한다(§8). */
    public FavoriteService(FavoriteRepository favoriteRepository,
                           PlaceRepository placeRepository,
                           PlaceImageRepository placeImageRepository,
                           CongestionForecastRepository forecastRepository) {
        this.favoriteRepository = favoriteRepository;
        this.placeRepository = placeRepository;
        this.placeImageRepository = placeImageRepository;
        this.forecastRepository = forecastRepository;
    }

    /**
     * 찜. 없는 장소면 PLACE_NOT_FOUND(3201). 이미 찜돼 있으면 그대로 성공.
     *
     * <p>존재 검사와 INSERT 사이에 같은 회원이 동시에 두 번 누르면 UNIQUE 충돌이 한 번 날 수 있다 -
     * 데이터는 제약이 지키고 화면은 재시도하면 되므로 트랜잭션을 꼬아 가며 잡지 않는다.
     */
    @Transactional
    public FavoriteStateResponse add(Long userId, Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND));
        if (!favoriteRepository.existsByUserIdAndPlaceId(userId, placeId)) {
            favoriteRepository.save(Favorite.builder().userId(userId).place(place).build());
        }
        return new FavoriteStateResponse(placeId, true);
    }

    /** 해제. 찜한 적 없는 장소·없는 장소여도 성공 - 결과 상태는 어차피 "찜 아님"이다. */
    @Transactional
    public FavoriteStateResponse remove(Long userId, Long placeId) {
        favoriteRepository.deleteByUserIdAndPlaceId(userId, placeId);
        return new FavoriteStateResponse(placeId, false);
    }

    /** 지도 하트 상태용 - 내가 찜한 장소 ID 전부(최근 순). */
    public List<Long> placeIds(Long userId) {
        return favoriteRepository.findPlaceIdsByUserId(userId);
    }

    /**
     * 마이페이지 목록(최근 찜한 순). 정렬 변경(이름·카테고리)은 목록이 작아 화면이 한다.
     *
     * <p>장소 본문은 fetch join 한 쿼리, 세부분류·대표사진·오늘 혼잡은 장소 ID 묶음으로 각 한 쿼리 -
     * 찜 수와 무관하게 쿼리 4~5개로 고정된다.
     */
    public List<FavoriteResponse> list(Long userId) {
        List<Favorite> favorites = favoriteRepository.findAllWithPlaceByUserId(userId);
        if (favorites.isEmpty()) {
            return List.of();
        }
        List<Long> placeIds = favorites.stream().map(f -> f.getPlace().getId()).toList();
        Map<Long, String> tagNames = firstByPlace(placeRepository.findApiTagNamesOf(placeIds));
        Map<Long, String> images = firstImages(placeImageRepository.findFirstImageOf(placeIds));
        Map<Long, BigDecimal> crowd = todayCrowd(placeIds);
        return favorites.stream()
                .map(f -> FavoriteResponse.from(f,
                        tagNames.get(f.getPlace().getId()),
                        images.get(f.getPlace().getId()),
                        crowd.get(f.getPlace().getId())))
                .toList();
    }

    /** (placeId, name) 행 → 장소당 첫 값. KTO 는 장소당 소분류 하나지만 둘이어도 죽지 않게 첫 건을 쓴다. */
    private static Map<Long, String> firstByPlace(List<Object[]> rows) {
        Map<Long, String> out = new HashMap<>();
        for (Object[] row : rows) {
            out.putIfAbsent((Long) row[0], (String) row[1]);
        }
        return out;
    }

    /** (placeId, thumbnailUrl, imageUrl) → 썸네일 우선, 없으면 원본. */
    private static Map<Long, String> firstImages(List<Object[]> rows) {
        Map<Long, String> out = new HashMap<>();
        for (Object[] row : rows) {
            String thumb = (String) row[1];
            out.putIfAbsent((Long) row[0], thumb != null ? thumb : (String) row[2]);
        }
        return out;
    }

    /**
     * 오늘(제주 기준)의 집중률 - 최신 발표 버전에서만. 예보가 없거나 오늘분이 없는 장소는 빠진다(화면은 '정보 없음').
     * 발표 버전을 고정하는 이유는 {@link CongestionForecastRepository} 클래스 주석 참고.
     */
    private Map<Long, BigDecimal> todayCrowd(List<Long> placeIds) {
        Optional<LocalDateTime> latest = forecastRepository.findLatestBaseAt();
        if (latest.isEmpty()) {
            return Map.of();
        }
        LocalDateTime today = PlaceNameNormalizer.jejuDayToUtc(LocalDate.now(PlaceNameNormalizer.JEJU_ZONE));
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Object[] row : forecastRepository.findRatesOn(latest.get(), today, placeIds)) {
            out.put((Long) row[0], (BigDecimal) row[1]);
        }
        return out;
    }
}
