package com.example.hangat.map.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.map.model.dto.PlaceDetailResponse;
import com.example.hangat.map.model.dto.PlaceListResponse;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.PlaceType;
import com.example.hangat.map.model.dto.PlaceImageResponse;
import com.example.hangat.map.repository.PlaceImageRepository;
import com.example.hangat.map.repository.PlaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 장소 조회 - 설계서 §2.1. 엔티티는 여기서 끊고 컨트롤러로 내보내지 않는다.
 *
 * <p>type별 if가 없다: 조건은 {@link PlaceType}의 조건표가 데이터로 들고 있고,
 * 이 클래스는 '어느 축에 조건이 걸렸나'만 보고 쿼리를 고른다.
 */
@Service
@Transactional(readOnly = true)
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final PlaceImageRepository placeImageRepository;

    /** 팀이 허용한 Lombok에 @RequiredArgsConstructor가 없어 직접 선언한다(§8). 단일 생성자라 Spring이 자동 주입한다. */
    public PlaceService(PlaceRepository placeRepository, PlaceImageRepository placeImageRepository) {
        this.placeRepository = placeRepository;
        this.placeImageRepository = placeImageRepository;
    }

    /** type이 없거나 비면 제주 전역 전체. 페이징 없음(수백 건 - §2.1). 리포지토리가 DTO를 주므로 변환 단계가 없다. */
    public List<PlaceListResponse> getPlaces(String type) {
        return PlaceType.from(type)
                .map(this::findList)
                .orElseGet(placeRepository::findListAll);
    }

    /** 검색 드롭다운은 상위만 보여준다 - 페이징을 소비할 화면이 없어 limit 고정. */
    private static final int SEARCH_LIMIT = 20;

    /**
     * 2글자 미만은 빈 결과 - 한 글자는 수백 건이 걸려 드롭다운이 의미를 잃는다.
     * region·categories 는 화면 필터 범위(없으면 전체). in은 빈 목록을 못 받아 여기서 분기한다.
     */
    public List<PlaceListResponse> searchPlaces(String q, String region, List<String> categories) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return List.of();
        }
        String regionCode = (region == null || region.isBlank()) ? null : region;
        if (categories == null || categories.isEmpty()) {
            return placeRepository.searchList(query, regionCode, PageRequest.of(0, SEARCH_LIMIT));
        }
        return placeRepository.searchListInCategories(query, regionCode, categories, PageRequest.of(0, SEARCH_LIMIT));
    }

    public PlaceDetailResponse getPlace(Long placeId) {
        Place place = placeRepository.findDetailById(placeId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND));
        List<PlaceImageResponse> images = placeImageRepository
                .findByPlaceIdOrderBySortOrder(placeId).stream()
                .map(PlaceImageResponse::from)
                .toList();
        return PlaceDetailResponse.from(place, findApiTag(placeId), images);
    }

    /**
     * 세부분류 1건. KTO는 장소당 소분류를 하나만 주지만, 그 전제가 깨져도 상세가 죽지 않게
     * 예외 대신 첫 건을 쓴다 - 목록 쪽에서 같은 장소가 두 줄로 드러나므로 이상은 그쪽에서 먼저 보인다.
     */
    private Object[] findApiTag(Long placeId) {
        List<Object[]> found = placeRepository.findApiTagOf(placeId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 조건표(카테고리·착한가격) 중 null이 아닌 축만 보고 쿼리를 고른다.
     * 두 값을 그대로 받아 넘기므로 조건표에 어떤 조합이 추가돼도(둘 다 null 포함) NPE가 나지 않는다.
     */
    private List<PlaceListResponse> findList(PlaceType type) {
        String categoryCode = type.getCategoryCode();
        Boolean goodPrice = type.getGoodPrice();
        if (categoryCode != null && goodPrice != null) {
            return placeRepository.findListOfCategoryAndGoodPrice(categoryCode, goodPrice);
        }
        if (categoryCode != null) {
            return placeRepository.findListOfCategory(categoryCode);
        }
        if (goodPrice != null) {
            return placeRepository.findListOfGoodPrice(goodPrice);
        }
        return placeRepository.findListAll();
    }
}
