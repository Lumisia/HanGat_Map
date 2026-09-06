package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseAccommodationUpdateRequest;
import com.example.hangat.course.model.CourseClaimRequest;
import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.course.service.CourseQueryService;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.user.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CourseAccommodationServiceTest {

    @Autowired CourseAccommodationService accommodationService;
    @Autowired CourseClaimService claimService;
    @Autowired CourseClaimTokenService tokenService;
    @Autowired CourseQueryService queryService;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseItemRepository itemRepository;
    @Autowired PlaceRepository placeRepository;
    @Autowired PlaceSourceMappingRepository mappingRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired PlaceCategoryRepository categoryRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;
    @Autowired EntityManager entityManager;
    @MockitoBean KakaoAccommodationProvider kakaoAccommodationProvider;

    private Region east;
    private PlaceCategory tourist;
    private PlaceCategory lodging;
    private DataSource kakao;
    private User owner;
    private User stranger;

    @BeforeEach
    void seedReferences() {
        east = regionRepository.findByCode("EAST")
                .orElseGet(() -> regionRepository.save(Region.builder()
                        .code("EAST").name("동부").displayOrder((byte) 1).build()));
        tourist = categoryRepository.findByCode("TOURIST")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("TOURIST").name("관광지").displayOrder((short) 1).build()));
        lodging = categoryRepository.findByCode("LODGING")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("LODGING").name("숙소").displayOrder((short) 4).build()));
        kakao = dataSourceRepository.findById("KAKAO_LOCAL")
                .orElseGet(() -> dataSourceRepository.save(DataSource.builder()
                        .code("KAKAO_LOCAL")
                        .displayName("Kakao Local")
                        .providerName("Kakao")
                        .attributionText("Kakao")
                        .displayOrder((short) 4)
                        .isActive(true)
                        .build()));
        owner = user("accommodation-owner@hangat.local");
        stranger = user("accommodation-stranger@hangat.local");
        when(kakaoAccommodationProvider.verify(anyList(), anyString(), anyString()))
                .thenAnswer(invocation -> verified(invocation.getArgument(2), "Kakao 검증 호텔"));
    }

    @Test
    void ready_코스는_claim_proof로_숙소를_저장하고_정확한_mapping을_재사용한다() throws Exception {
        Course course = readyCourse();
        PlaceSourceMapping mapping = mapping("kakao-hotel-1", "저장된 호텔");
        CourseClaimTokenService.ClaimProof proof = tokenService.issue(course.getId());

        AccommodationDto saved = accommodationService.update(
                course.getId(), request("kakao-hotel-1", "요청 이름", proof.token()), null);

        assertThat(course.getAccommodationSourceMapping().getId()).isEqualTo(mapping.getId());
        assertThat(saved.getSourceCode()).isEqualTo("KAKAO_LOCAL");
        assertThat(saved.getSourcePlaceId()).isEqualTo("kakao-hotel-1");
        assertThat(saved.getPlaceName()).isEqualTo("Kakao 검증 호텔");
        assertThat(saved.getAddress()).isEqualTo("Kakao 지번");
        assertThat(saved.getRoadAddress()).isEqualTo("Kakao 도로명");
        assertThat(saved.getLatitude()).isEqualTo(33.45);
        assertThat(saved.getLongitude()).isEqualTo(126.55);
        assertThat(mappingRepository.count()).isEqualTo(1);
    }

    @Test
    void mapping이_없으면_공식_region과_LODGING으로_장소와_mapping을_생성한다() throws Exception {
        Course course = readyCourse();
        CourseClaimTokenService.ClaimProof proof = tokenService.issue(course.getId());

        AccommodationDto saved = accommodationService.update(
                course.getId(), request("kakao-new-hotel", "신규 호텔", proof.token()), null);

        PlaceSourceMapping mapping = mappingRepository
                .findBySourceCodeAndSourcePlaceId("KAKAO_LOCAL", "kakao-new-hotel")
                .orElseThrow();
        assertThat(saved.getPlaceId()).isEqualTo(mapping.getPlace().getId());
        assertThat(mapping.getPlace().getRegion().getCode()).isEqualTo("EAST");
        assertThat(mapping.getPlace().getPrimaryCategory().getCode()).isEqualTo("LODGING");
    }

    @Test
    void 기존_숙소_교체는_FK만_바꾸고_CourseItem을_보존한다() throws Exception {
        Course course = readyCourse();
        CourseItem item = itemRepository.findItemsWithPlace(course.getId()).get(0);
        CourseClaimTokenService.ClaimProof proof = tokenService.issue(course.getId());
        List<Long> beforeIds = itemRepository.findItemsWithPlace(course.getId())
                .stream().map(CourseItem::getId).toList();

        accommodationService.update(
                course.getId(), request("hotel-first", "첫 호텔", proof.token()), null);
        accommodationService.update(
                course.getId(), request("hotel-second", "둘째 호텔", proof.token()), null);

        List<CourseItem> after = itemRepository.findItemsWithPlace(course.getId());
        assertThat(after).extracting(CourseItem::getId).containsExactlyElementsOf(beforeIds);
        assertThat(after).singleElement().satisfies(savedItem -> {
            assertThat(savedItem.getId()).isEqualTo(item.getId());
            assertThat(savedItem.getPlace().getName()).isEqualTo("비자림");
            assertThat(savedItem.getDayNo()).isEqualTo((short) 1);
            assertThat(savedItem.getPosition()).isEqualTo((short) 1);
        });
        assertThat(course.getAccommodationSourceMapping().getSourcePlaceId())
                .isEqualTo("hotel-second");
    }

    @Test
    void 숙소는_claim_전후_유지되고_GET에서_동일_identity로_복원된다() throws Exception {
        Course course = readyCourse();
        CourseClaimTokenService.ClaimProof proof = tokenService.issue(course.getId());
        accommodationService.update(
                course.getId(), request("claim-hotel", "클레임 호텔", proof.token()), null);

        claimService.claim(
                course.getId(), owner.getId(), new CourseClaimRequest(proof.token(), "저장 코스"));
        entityManager.flush();
        entityManager.clear();

        CourseDetailResponse detail = queryService.detail(course.getId(), owner.getId());
        assertThat(detail.accommodation()).isNotNull();
        assertThat(detail.accommodation().getSourceCode()).isEqualTo("KAKAO_LOCAL");
        assertThat(detail.accommodation().getSourcePlaceId()).isEqualTo("claim-hotel");
        assertThat(detail.accommodation().getPlaceName()).isEqualTo("Kakao 검증 호텔");
    }

    @Test
    void saved_코스는_owner만_숙소를_변경한다() throws Exception {
        Course course = readyCourse();
        course.markSaved(owner, "내 코스");
        CourseAccommodationUpdateRequest request = request("saved-hotel", "저장 호텔", null);

        assertThatThrownBy(() -> accommodationService.update(
                course.getId(), request, stranger.getId()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(BaseResponseStatus.COURSE_FORBIDDEN));
        assertThatThrownBy(() -> accommodationService.update(course.getId(), request, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(BaseResponseStatus.LOGIN_REQUIRED));

        AccommodationDto saved = accommodationService.update(
                course.getId(), request, owner.getId());
        assertThat(saved.getSourcePlaceId()).isEqualTo("saved-hotel");
    }

    @Test
    void 잘못된_claim_SAMPLE_DELETED_없는_코스는_변경하지_못한다() throws Exception {
        Course ready = readyCourse();
        Course other = readyCourse();
        CourseClaimTokenService.ClaimProof wrong = tokenService.issue(other.getId());
        assertStatus(
                () -> accommodationService.update(
                        ready.getId(), request("wrong-proof", "호텔", wrong.token()), null),
                BaseResponseStatus.COURSE_CLAIM_INVALID);

        Course sample = courseRepository.save(Course.builder()
                .courseType(CourseType.SAMPLE).title("샘플")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 2))
                .transport(Transport.RENTAL_CAR)
                .build());
        sample.markReady();
        assertStatus(
                () -> accommodationService.update(
                        sample.getId(), request(
                                "sample-hotel", "호텔", tokenService.issue(sample.getId()).token()), null),
                BaseResponseStatus.COURSE_FORBIDDEN);

        Course deleted = readyCourse();
        deleted.softDelete();
        assertStatus(
                () -> accommodationService.update(
                        deleted.getId(), request(
                                "deleted-hotel", "호텔", tokenService.issue(deleted.getId()).token()), null),
                BaseResponseStatus.COURSE_NOT_FOUND);
        assertStatus(
                () -> accommodationService.update(
                        Long.MAX_VALUE, request("missing-hotel", "호텔", "unused"), null),
                BaseResponseStatus.COURSE_NOT_FOUND);
    }

    @Test
    void 공개_PATCH는_security에_막히지_않고_null과_잘못된_payload를_400으로_응답한다() throws Exception {
        mockMvc.perform(patch("/courses/{courseId}/accommodation", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accommodation\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3000));

        mockMvc.perform(patch("/courses/{courseId}/accommodation", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accommodation":{
                                  "source_code":"UNKNOWN",
                                  "source_place_id":"",
                                  "place_name":""
                                }}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3000));
    }

    private Course readyCourse() {
        Course course = courseRepository.save(Course.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 2))
                .transport(Transport.RENTAL_CAR)
                .build());
        course.markReady();
        item(course, touristPlace("비자림"));
        return course;
    }

    private CourseItem item(Course course, Place place) {
        return itemRepository.save(CourseItem.builder()
                .course(course)
                .place(place)
                .dayNo((short) 1)
                .position((short) 1)
                .visitDate(course.getStartDate())
                .recommendationReason("원래 일정")
                .build());
    }

    private Place touristPlace(String name) {
        return placeRepository.save(Place.builder()
                .region(east)
                .primaryCategory(tourist)
                .name(name)
                .normalizedName(name)
                .latitude(new BigDecimal("33.4500000"))
                .longitude(new BigDecimal("126.5500000"))
                .build());
    }

    private PlaceSourceMapping mapping(String sourcePlaceId, String name) {
        Place place = placeRepository.save(Place.builder()
                .region(east)
                .primaryCategory(lodging)
                .name(name)
                .normalizedName(name)
                .latitude(new BigDecimal("33.4500000"))
                .longitude(new BigDecimal("126.5500000"))
                .build());
        return mappingRepository.save(PlaceSourceMapping.builder()
                .place(place)
                .source(kakao)
                .sourcePlaceId(sourcePlaceId)
                .isActive(true)
                .build());
    }

    private CourseAccommodationUpdateRequest request(
            String sourcePlaceId,
            String placeName,
            String claimToken
    ) {
        AccommodationDto accommodation = objectMapper.convertValue(Map.of(
                "source_code", "KAKAO_LOCAL",
                "source_place_id", sourcePlaceId,
                "place_name", placeName,
                "address", "제주특별자치도 제주시 구좌읍",
                "road_address", "제주특별자치도 제주시 숙소로 1",
                "latitude", 33.45,
                "longitude", 126.55,
                "region", "EAST"), AccommodationDto.class);
        return new CourseAccommodationUpdateRequest(accommodation, claimToken);
    }

    private User user(String email) {
        User user = User.signUpWithEmail(
                email,
                "bcrypt-hash",
                email.substring(0, email.indexOf('@')));
        entityManager.persist(user);
        return user;
    }

    private KakaoAccommodationProvider.VerifiedAccommodation verified(String id, String name) {
        return new KakaoAccommodationProvider.VerifiedAccommodation(
                new KakaoPlace(id, name, "Kakao 지번", "Kakao 도로명",
                        new BigDecimal("33.4500000"), new BigDecimal("126.5500000"),
                        "AD5", "여행 > 숙박", "064-000-0000", "https://place.map.kakao.com/" + id, 1000),
                east);
    }

    private void assertStatus(Runnable invocation, BaseResponseStatus expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(expected));
    }
}
