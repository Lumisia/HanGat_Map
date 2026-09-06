package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CandidateIdentity;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.facts.ExternalClassificationFact;
import com.example.hangat.course.facts.InternalPlaceCategory;
import com.example.hangat.course.facts.PlaceFact;
import com.example.hangat.course.facts.UserConstraint;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.GenerationReason;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({CoursePersistenceService.class, CoursePlaceResolver.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CoursePersistenceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Autowired
    private CoursePersistenceService persistenceService;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private CourseItemRepository courseItemRepository;
    @Autowired
    private PlaceRepository placeRepository;
    @Autowired
    private PlaceSourceMappingRepository mappingRepository;
    @Autowired
    private RegionRepository regionRepository;
    @Autowired
    private PlaceCategoryRepository categoryRepository;
    @Autowired
    private DataSourceRepository dataSourceRepository;

    @BeforeEach
    void setUpReferences() {
        courseItemRepository.deleteAll();
        mappingRepository.deleteAll();
        courseRepository.deleteAll();
        placeRepository.deleteAll();
        categoryRepository.deleteAll();
        regionRepository.deleteAll();
        dataSourceRepository.deleteAll();

        regionRepository.save(Region.builder()
                .code("EAST").name("동부").displayOrder((byte) 1).build());
        categoryRepository.save(PlaceCategory.builder()
                .code("TOURIST").name("관광지").displayOrder((short) 1).build());
        categoryRepository.save(PlaceCategory.builder()
                .code("CAFE").name("카페").displayOrder((short) 2).build());
        dataSourceRepository.save(dataSource("KTO", (short) 1));
        dataSourceRepository.save(dataSource("KAKAO_LOCAL", (short) 2));
    }

    @Test
    void persistsFactsDirectlyWithoutAiInputOrOriginalCandidates() throws Exception {
        CourseCandidate candidate = candidate(
                "candidate-kto-1001", null, "KTO", "1001",
                "성산일출봉", "제주특별자치도 서귀포시 성산읍 일출로 284-12",
                "제주특별자치도 서귀포시 성산읍 성산리 1", "EAST", "TOURIST",
                UserConstraint.want(LocalDate.of(2026, 8, 27), LocalTime.of(9, 0)),
                "A01");
        CourseAiResultDto result = result(
                "candidate-kto-1001", "2026-08-27", "09:00");

        CoursePersistenceResult persisted = persistenceService.persist(
                request(), facts(candidate), result,
                metadata(GenerationReason.INITIAL, "course-ai-2"));

        assertThat(persisted.course().getId()).isNotNull();
        assertThat(persisted.course().getStatus()).isEqualTo(CourseStatus.READY);
        assertThat(persisted.course().getUser()).isNull();
        assertThat(persisted.course().getGenerationReason())
                .isEqualTo(com.example.hangat.course.model.enums.GenerationReason.INITIAL);
        assertThat(persisted.course().getAlgorithmVersion()).isEqualTo("course-ai-2");

        CourseItem item = persisted.itemsByCandidateId().get("candidate-kto-1001");
        assertThat(item.getId()).isNotNull();
        assertThat(item.getPlace().getId()).isNotNull();
        assertThat(item.getPlace().getRoadAddress())
                .isEqualTo("제주특별자치도 서귀포시 성산읍 일출로 284-12");
        assertThat(item.getPlace().getLotAddress())
                .isEqualTo("제주특별자치도 서귀포시 성산읍 성산리 1");
        assertThat(item.getDayNo()).isEqualTo((short) 1);
        assertThat(item.getPosition()).isEqualTo((short) 1);
        assertThat(item.getVisitDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(item.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(item.getItemSource()).isEqualTo(CourseItemSource.USER_FIXED);
        assertThat(item.getRecommendationReason()).isEqualTo("사실 데이터에 근거한 추천 이유");
        assertThat(item.getInboundDistanceM()).isNull();
        assertThat(item.getInboundTravelMinutes()).isNull();
        assertThat(item.getPlannedCongestionForecast()).isNull();
        assertThat(item.getPlannedWeatherForecast()).isNull();
        assertThat(item.getRecommendationScore()).isNull();
        assertThat(item.getRecommendationReasonCode()).isNull();
        assertThat(mappingRepository.findBySourceCodeAndSourcePlaceId("KTO", "1001"))
                .get().extracting(mapping -> mapping.getPlace().getId())
                .isEqualTo(item.getPlace().getId());
        assertThat(persisted.categoryNamesByCandidateId().get("candidate-kto-1001"))
                .isEqualTo("관광지");
    }

    @Test
    void reusesExactSourceMappingWithoutDuplicatingPlace() throws Exception {
        CourseCandidate candidate = candidate(
                "candidate-2001", null, "KTO", "2001", "비자림",
                null, "제주특별자치도 제주시 구좌읍 비자숲길 55",
                "EAST", "TOURIST", UserConstraint.none(), "A01");

        CoursePersistenceResult first = persistenceService.persist(
                request(), facts(candidate), result("candidate-2001", "2026-08-27", "10:00"),
                metadata(GenerationReason.INITIAL, null));
        CoursePersistenceResult second = persistenceService.persist(
                request(), facts(candidate), result("candidate-2001", "2026-08-27", "10:00"),
                metadata(GenerationReason.USER_REGENERATE, null));

        assertThat(courseRepository.count()).isEqualTo(2);
        assertThat(placeRepository.count()).isEqualTo(1);
        assertThat(mappingRepository.count()).isEqualTo(1);
        assertThat(first.itemsByCandidateId().get("candidate-2001").getPlace().getId())
                .isEqualTo(second.itemsByCandidateId().get("candidate-2001").getPlace().getId());
        assertThat(second.course().getGenerationReason())
                .isEqualTo(com.example.hangat.course.model.enums.GenerationReason.USER_REGENERATE);
    }

    @Test
    void reusesExistingPlaceIdAndAddsOnlyNonConflictingSourceMapping() throws Exception {
        Place existing = savePlace("기존 장소", "기존 도로명", "EAST", "TOURIST");
        CourseCandidate candidate = candidate(
                "candidate-existing", existing.getId(), "KAKAO_LOCAL", "kakao-existing",
                "외부 표시명", "외부 도로명", "외부 지번", "EAST", "TOURIST",
                UserConstraint.none(), null);

        CoursePersistenceResult persisted = persistenceService.persist(
                request(), facts(candidate),
                result("candidate-existing", "2026-08-27", "11:00"),
                metadata(GenerationReason.INITIAL, null));

        assertThat(persisted.itemsByCandidateId().get("candidate-existing").getPlace().getId())
                .isEqualTo(existing.getId());
        assertThat(placeRepository.count()).isEqualTo(1);
        assertThat(mappingRepository.findBySourceCodeAndSourcePlaceId(
                "KAKAO_LOCAL", "kakao-existing"))
                .get().extracting(mapping -> mapping.getPlace().getId())
                .isEqualTo(existing.getId());
    }

    @Test
    void doesNotMergeSameNameAndCoordinatesAcrossProviders() throws Exception {
        CourseCandidate kto = candidate(
                "candidate-kto", null, "KTO", "125266", "비자림",
                null, "제주특별자치도 제주시 구좌읍 비자숲길 55",
                "EAST", "TOURIST", UserConstraint.none(), "A01");
        CourseCandidate kakao = candidate(
                "candidate-kakao", null, "KAKAO_LOCAL", "kakao-125266", "비자림",
                null, "제주특별자치도 제주시 구좌읍 비자숲길 55",
                "EAST", "TOURIST", UserConstraint.none(), null);

        persistenceService.persist(
                request(), facts(kto), result("candidate-kto", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null));
        persistenceService.persist(
                request(), facts(kakao), result("candidate-kakao", "2026-08-27", "10:00"),
                metadata(GenerationReason.INITIAL, null));

        assertThat(placeRepository.count()).isEqualTo(2);
        assertThat(mappingRepository.count()).isEqualTo(2);
        Long ktoPlaceId = mappingRepository.findBySourceCodeAndSourcePlaceId("KTO", "125266")
                .orElseThrow().getPlace().getId();
        Long kakaoPlaceId = mappingRepository.findBySourceCodeAndSourcePlaceId(
                "KAKAO_LOCAL", "kakao-125266").orElseThrow().getPlace().getId();
        assertThat(kakaoPlaceId).isNotEqualTo(ktoPlaceId);
    }

    @Test
    void rejectsConflictingInternalAndExternalIdentityAtomically() throws Exception {
        Place mappedPlace = savePlace("매핑 장소", null, "EAST", "TOURIST");
        Place requestedPlace = savePlace("내부 장소", null, "EAST", "TOURIST");
        mappingRepository.save(sourceMapping(mappedPlace, "KTO", "conflict-1"));
        CourseCandidate candidate = candidate(
                "candidate-conflict", requestedPlace.getId(), "KTO", "conflict-1",
                "내부 장소", null, null, "EAST", "TOURIST",
                UserConstraint.none(), "A01");

        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(candidate),
                result("candidate-conflict", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("충돌");

        assertThat(courseRepository.count()).isZero();
        assertThat(courseItemRepository.count()).isZero();
        assertThat(mappingRepository.count()).isEqualTo(1);
    }

    @Test
    void usesNormalizedInternalCategoryWithoutReinterpretingExternalClassification()
            throws Exception {
        CourseCandidate candidate = candidate(
                "candidate-a04", null, "KTO", "a04-1", "내부 분류 장소",
                null, null, "EAST", "TOURIST", UserConstraint.none(), "A04");

        CoursePersistenceResult persisted = persistenceService.persist(
                request(), facts(candidate), result("candidate-a04", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null));

        assertThat(persisted.itemsByCandidateId().get("candidate-a04")
                .getPlace().getPrimaryCategory().getCode()).isEqualTo("TOURIST");
    }

    @Test
    void rejectsMissingCategoryAndUnknownOrUnregisteredRegionBeforeCourseInsert()
            throws Exception {
        CourseCandidate missingCategory = candidate(
                "candidate-no-category", null, "KTO", "no-category", "미분류 장소",
                null, null, "EAST", null, UserConstraint.none(), "A04");
        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(missingCategory),
                result("candidate-no-category", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("카테고리");

        CourseCandidate unknownRegion = candidate(
                "candidate-unknown", null, "KTO", "unknown", "권역 미상",
                null, null, "UNKNOWN", "TOURIST", UserConstraint.none(), "A01");
        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(unknownRegion),
                result("candidate-unknown", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("권역");

        CourseCandidate missingReference = candidate(
                "candidate-north", null, "KTO", "north", "북부 장소",
                null, null, "NORTH", "TOURIST", UserConstraint.none(), "A01");
        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(missingReference),
                result("candidate-north", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("등록된 권역");

        assertThat(courseRepository.count()).isZero();
        assertThat(courseItemRepository.count()).isZero();
        assertThat(placeRepository.count()).isZero();
        assertThat(mappingRepository.count()).isZero();
    }

    @Test
    void projectsAiArrayOrderAndCandidateConstraintsToCourseItems() throws Exception {
        CourseCandidate fixedWant = candidate(
                "fixed-want", null, "KAKAO_LOCAL", "fixed-1", "고정 장소",
                null, null, "EAST", "TOURIST",
                UserConstraint.want(LocalDate.of(2026, 8, 28), null), null);
        CourseCandidate flexibleWant = candidate(
                "flexible-want", null, "KAKAO_LOCAL", "want-2", "유연 WANT",
                null, null, "EAST", "CAFE", UserConstraint.want(null, null), null);
        CourseCandidate general = candidate(
                "general", null, "KTO", "general-3", "일반 장소",
                null, null, "EAST", "TOURIST", UserConstraint.none(), "A01");
        CourseAiResultDto result = new CourseAiResultDto("2.0", List.of(
                new CourseAiResultDto.DayDto(
                        LocalDate.of(2026, 8, 27),
                        List.of(item("general", "09:00"))),
                new CourseAiResultDto.DayDto(
                        LocalDate.of(2026, 8, 28),
                        List.of(
                                item("fixed-want", "11:00"),
                                item("flexible-want", "14:00")))));

        CoursePersistenceResult persisted = persistenceService.persist(
                request(), facts(fixedWant, flexibleWant, general), result,
                metadata(GenerationReason.INITIAL, null));
        List<CourseItem> items = courseItemRepository.findItemsWithPlace(
                persisted.course().getId());

        assertThat(items).extracting(CourseItem::getDayNo)
                .containsExactly((short) 1, (short) 2, (short) 2);
        assertThat(items).extracting(CourseItem::getPosition)
                .containsExactly((short) 1, (short) 1, (short) 2);
        assertThat(items).extracting(CourseItem::getVisitDate)
                .containsExactly(
                        LocalDate.of(2026, 8, 27),
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 28));
        assertThat(items).extracting(CourseItem::getItemSource)
                .containsExactly(
                        CourseItemSource.AI_RECOMMENDED,
                        CourseItemSource.USER_FIXED,
                        CourseItemSource.AI_RECOMMENDED);
    }

    @Test
    void rejectsUnknownResultCandidateAndDuplicateFactCandidateIdsBeforeInsert()
            throws Exception {
        CourseCandidate candidate = candidate(
                "candidate-known", null, "KTO", "known-1", "알려진 장소",
                null, null, "EAST", "TOURIST", UserConstraint.none(), "A01");

        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(candidate),
                result("candidate-unknown", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("저장할 수 없는 AI 후보");

        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(candidate, candidate),
                result("candidate-known", "2026-08-27", "09:00"),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복 candidateId");

        assertThat(courseRepository.count()).isZero();
        assertThat(courseItemRepository.count()).isZero();
    }

    @Test
    void persistsKakaoOnlyWantWithoutKtoFallbackAndKeepsIdentitySystemsSeparate()
            throws Exception {
        CourseCandidate candidate = candidate(
                "request-want-1", null, "KAKAO_LOCAL", "kakao-document-9001",
                "카카오 숲길", "제주특별자치도 제주시 구좌읍 비자숲길 1",
                null, "EAST", "TOURIST",
                UserConstraint.want(LocalDate.of(2026, 8, 27), LocalTime.of(10, 30)), null);

        CoursePersistenceResult persisted = persistenceService.persist(
                request(), facts(candidate),
                result("request-want-1", "2026-08-27", "10:30"),
                metadata(GenerationReason.INITIAL, null));

        CourseItem item = persisted.itemsByCandidateId().get("request-want-1");
        assertThat(item.getPlace().getId()).isNotNull();
        assertThat(item.getPlace().getId().toString())
                .isNotEqualTo("request-want-1")
                .isNotEqualTo("kakao-document-9001");
        assertThat(mappingRepository.findBySourceCodeAndSourcePlaceId(
                "KAKAO_LOCAL", "kakao-document-9001"))
                .get().extracting(mapping -> mapping.getPlace().getId())
                .isEqualTo(item.getPlace().getId());
        assertThat(mappingRepository.findBySourceCodeAndSourcePlaceId(
                "KTO", "kakao-document-9001")).isEmpty();
    }

    @Test
    void rejectsEmptyScheduleAndOverlongReasonWithoutPartialRows() throws Exception {
        CourseCandidate candidate = candidate(
                "candidate-reason", null, "KTO", "reason-1", "추천 장소",
                null, null, "EAST", "TOURIST", UserConstraint.none(), "A01");

        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(candidate), new CourseAiResultDto("2.0", List.of()),
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("방문 일정");

        CourseAiResultDto overlong = new CourseAiResultDto("2.0", List.of(
                new CourseAiResultDto.DayDto(LocalDate.of(2026, 8, 27), List.of(
                        new CourseAiResultDto.ItemDto(
                                "candidate-reason", LocalTime.of(9, 0), "가".repeat(301))))));
        assertThatThrownBy(() -> persistenceService.persist(
                request(), facts(candidate), overlong,
                metadata(GenerationReason.INITIAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("300자");

        assertThat(courseRepository.count()).isZero();
        assertThat(courseItemRepository.count()).isZero();
    }

    private Place savePlace(
            String name,
            String roadAddress,
            String regionCode,
            String categoryCode
    ) {
        Region region = regionRepository.findByCode(regionCode)
                .orElseThrow();
        PlaceCategory category = categoryRepository
                .findByCode(categoryCode).orElseThrow();
        return placeRepository.save(Place.builder()
                .region(region)
                .primaryCategory(category)
                .name(name)
                .normalizedName(normalizeName(name))
                .roadAddress(roadAddress)
                .latitude(new BigDecimal("33.4580000"))
                .longitude(new BigDecimal("126.9420000"))
                .build());
    }

    private PlaceSourceMapping sourceMapping(
            Place place,
            String sourceCode,
            String sourcePlaceId
    ) {
        return PlaceSourceMapping.builder()
                .place(place)
                .source(dataSourceRepository.findById(sourceCode).orElseThrow())
                .sourcePlaceId(sourcePlaceId)
                .isActive(true)
                .build();
    }

    private DataSource dataSource(String code, short order) {
        return DataSource.builder()
                .code(code)
                .displayName(code)
                .providerName("테스트 제공자")
                .attributionText("테스트 출처")
                .displayOrder(order)
                .build();
    }

    private CourseGenerationFacts facts(CourseCandidate... candidates) {
        return new CourseGenerationFacts(List.of(candidates), List.of(), List.of());
    }

    private CourseCandidate candidate(
            String candidateId,
            Long placeId,
            String sourceCode,
            String sourcePlaceId,
            String name,
            String roadAddress,
            String address,
            String regionCode,
            String internalCategoryCode,
            UserConstraint constraint,
            String externalCategoryCode
    ) {
        List<ExternalClassificationFact> classifications = externalCategoryCode == null
                ? List.of()
                : List.of(new ExternalClassificationFact(
                        sourceCode, externalCategoryCode, null, null, null));
        InternalPlaceCategory category = internalCategoryCode == null
                ? null
                : new InternalPlaceCategory(
                        null, internalCategoryCode, categoryName(internalCategoryCode));
        return new CourseCandidate(
                new CandidateIdentity(candidateId, placeId, sourceCode, sourcePlaceId),
                new PlaceFact(
                        name, address, roadAddress,
                        new BigDecimal("33.4580000"),
                        new BigDecimal("126.9420000"), null),
                constraint,
                regionCode,
                classifications,
                category,
                List.of(),
                List.of(),
                null);
    }

    private String categoryName(String code) {
        return switch (code) {
            case "TOURIST" -> "관광지";
            case "CAFE" -> "카페";
            default -> throw new IllegalArgumentException("지원하지 않는 테스트 카테고리");
        };
    }

    private CourseGenerationMetadata metadata(
            GenerationReason generationReason,
            String algorithmVersion
    ) {
        return new CourseGenerationMetadata(generationReason, algorithmVersion, null);
    }

    private CourseRequestDto request() throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date":"2026-08-27",
                  "end_date":"2026-08-29",
                  "people":2,
                  "budget_total":500000,
                  "transport":"RENTAL_CAR",
                  "course_regions":[],
                  "course_styles":[{"code":"NATURE","weight":1}],
                  "course_place_preferences":[]
                }
                """, CourseRequestDto.class);
    }

    private CourseAiResultDto result(
            String candidateId,
            String date,
            String startTime
    ) {
        return new CourseAiResultDto("2.0", List.of(
                new CourseAiResultDto.DayDto(
                        LocalDate.parse(date), List.of(item(candidateId, startTime)))));
    }

    private CourseAiResultDto.ItemDto item(String candidateId, String startTime) {
        return new CourseAiResultDto.ItemDto(
                candidateId,
                LocalTime.parse(startTime),
                "사실 데이터에 근거한 추천 이유");
    }

    private String normalizeName(String value) {
        return value.replaceAll("[\\s\\p{P}\\p{S}]+", "").toLowerCase();
    }
}
