package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.entity.CourseItemCost;
import com.example.hangat.course.model.entity.CoursePreset;
import com.example.hangat.course.model.enums.CostAccuracy;
import com.example.hangat.course.model.enums.CostCategory;
import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.GenerationReason;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseItemCostRepository;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CoursePresetRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import com.example.hangat.user.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 코스 도메인 엔티티 스모크 - 명세서 19.0/21.0/25.0/26.0 매핑과
 * 상태 전이·스왑·비용 불변식이 DB 왕복 후에도 지켜지는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CourseDomainTest {

    private static final LocalDate 출발일 = LocalDate.of(2026, 9, 12);

    @Autowired EntityManager em;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseItemRepository itemRepository;
    @Autowired CourseItemCostRepository costRepository;
    @Autowired CoursePresetRepository presetRepository;
    @Autowired PlaceRepository placeRepository;
    @Autowired CongestionForecastRepository forecastRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired PlaceCategoryRepository categoryRepository;

    private Place 성산일출봉;
    private Place 혼인지;
    private CongestionForecast 혼인지예보;

    @BeforeEach
    void seed() {
        Region east = regionRepository.findByCode("EAST")
                .orElseGet(() -> regionRepository.save(Region.builder()
                        .code("EAST").name("동부").displayOrder((byte) 3).build()));
        PlaceCategory tourist = categoryRepository.findByCode("TOURIST")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("TOURIST").name("관광지").build()));
        DataSource source = em.find(DataSource.class, "KTO_CNCTR");
        if (source == null) {
            source = DataSource.builder()
                    .code("KTO_CNCTR").displayName("한국관광공사 관광지별 집중률")
                    .providerName("한국관광공사").attributionText("한국관광공사")
                    .displayOrder((short) 2).isActive(true)
                    .build();
            em.persist(source);
        }

        성산일출봉 = placeRepository.save(place(east, tourist, "성산일출봉", 33.4581, 126.9425));
        혼인지 = placeRepository.save(place(east, tourist, "혼인지", 33.4335, 126.8930));
        혼인지예보 = forecastRepository.save(CongestionForecast.of(
                혼인지, source, PlaceNameNormalizer.jejuDayToUtc(출발일),
                LocalDateTime.of(2026, 9, 10, 0, 0), new BigDecimal("23.00")));
    }

    private Place place(Region region, PlaceCategory category, String name, double lat, double lng) {
        return Place.builder()
                .name(name).normalizedName(name)
                .region(region).primaryCategory(category)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build();
    }

    private Course 임시코스() {
        return courseRepository.save(Course.builder()
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .build());
    }

    private CourseItem 일정(Course course, Place place, int dayNo, int position) {
        return itemRepository.save(CourseItem.builder()
                .course(course).place(place)
                .dayNo((short) dayNo).position((short) position)
                .visitDate(출발일.plusDays(dayNo - 1))
                .build());
    }

    @Test
    void 코스와_일정을_저장하면_기본값까지_그대로_돌아온다() {
        Course course = 임시코스();
        일정(course, 성산일출봉, 1, 1);
        CourseItem 둘째 = itemRepository.save(CourseItem.builder()
                .course(course).place(혼인지)
                .dayNo((short) 1).position((short) 2)
                .visitDate(출발일)
                .plannedCongestionForecast(혼인지예보)
                .recommendationScore(new BigDecimal("87.1234"))
                .recommendationReasonCode("CONGESTION")
                .recommendationReason("이 날짜 혼잡 예보가 한산이에요")
                .build());
        costRepository.save(CourseItemCost.unknown(course, 둘째, CostCategory.ACTIVITY));
        em.flush();
        em.clear();

        Course loaded = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(loaded.getCourseType()).isEqualTo(CourseType.USER);
        assertThat(loaded.getStatus()).isEqualTo(CourseStatus.GENERATING);
        assertThat(loaded.getGenerationReason()).isEqualTo(GenerationReason.INITIAL);
        assertThat(loaded.getPeople()).isEqualTo((short) 1);
        assertThat(loaded.getUser()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();

        List<CourseItem> items = itemRepository.findItemsWithPlace(course.getId());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getPlace().getName()).isEqualTo("성산일출봉");
        assertThat(items.get(0).getItemSource()).isEqualTo(CourseItemSource.AI_RECOMMENDED);
        assertThat(items.get(0).getInboundDistanceM()).isNull();   // 첫 항목 - 0으로 채우지 않는다
        assertThat(items.get(1).getPlannedCongestionForecast().getRate())
                .isEqualByComparingTo("23.00");

        List<CourseItemCost> costs = costRepository.findByCourseId(course.getId());
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).getAccuracyType()).isEqualTo(CostAccuracy.UNKNOWN);
        assertThat(costs.get(0).getCurrency()).isEqualTo("KRW");
        assertThat(costs.get(0).getCalculatedAt()).isNotNull();
    }

    @Test
    void 같은_일차_같은_순서는_저장이_거부된다() {
        Course course = 임시코스();
        일정(course, 성산일출봉, 1, 1);
        em.flush();

        assertThatThrownBy(() -> itemRepository.saveAndFlush(CourseItem.builder()
                .course(course).place(혼인지)
                .dayNo((short) 1).position((short) 1)
                .visitDate(출발일)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                // NOT NULL 등 다른 무결성 오류로 대신 통과하는 허위 통과 방지 - 제약 이름까지 못 박는다(H2는 대문자)
                .hasMessageContaining("UK_COURSE_ITEMS_DAY_POSITION");
    }

    @Test
    void 스왑하면_교체_흔적이_남고_엔진_점수는_비워진다() {
        Course course = 임시코스();
        CourseItem item = itemRepository.save(CourseItem.builder()
                .course(course).place(성산일출봉)
                .dayNo((short) 1).position((short) 1)
                .visitDate(출발일)
                .recommendationScore(new BigDecimal("91.0000"))
                .inboundDistanceM(4200).inboundTravelMinutes((short) 12)
                .build());

        item.replaceWith(혼인지, 혼인지예보, "CONGESTION", "직접 바꾼 곳이에요");
        em.flush();
        em.clear();

        CourseItem loaded = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(loaded.getPlace().getName()).isEqualTo("혼인지");
        assertThat(loaded.getReplacedFromPlace().getName()).isEqualTo("성산일출봉");
        assertThat(loaded.getItemSource()).isEqualTo(CourseItemSource.REPLACEMENT);
        assertThat(loaded.getRecommendationScore()).isNull();
        // 이전 장소 기준 이동거리·시간도 낡은 값 - 스왑이 비워야 화면이 거짓말을 안 한다
        assertThat(loaded.getInboundDistanceM()).isNull();
        assertThat(loaded.getInboundTravelMinutes()).isNull();
        assertThat(loaded.getRecommendationReason()).isEqualTo("직접 바꾼 곳이에요");
        // 순서는 그대로 - 스왑은 재배열이 아니라 제자리 교체다
        assertThat(loaded.getDayNo()).isEqualTo((short) 1);
        assertThat(loaded.getPosition()).isEqualTo((short) 1);
    }

    @Test
    void 저장하면_소유자와_제목_저장시각이_한번에_확정된다() {
        User 회원 = 회원가입("saver@hangat.local");
        Course course = 임시코스();
        course.markReady();
        course.markSaved(회원, "동부 한산 1박2일");
        em.flush();
        em.clear();

        Course loaded = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CourseStatus.SAVED);
        assertThat(loaded.getUser().getId()).isEqualTo(회원.getId());
        assertThat(loaded.getTitle()).isEqualTo("동부 한산 1박2일");
        assertThat(loaded.getSavedAt()).isNotNull();

        assertThat(courseRepository.findByUserIdAndStatus(
                회원.getId(), CourseStatus.SAVED, org.springframework.data.domain.PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }

    private User 회원가입(String email) {
        User user = User.signUpWithEmail(email, "bcrypt-해시-자리", "코스테스터-" + email.charAt(0));
        em.persist(user);
        return user;
    }

    @Test
    void 프리셋의_최신_READY_샘플만_공개_대상으로_고른다() {
        CoursePreset preset = presetRepository.save(CoursePreset.builder()
                .code("EAST_2DAYS").name("동부 1박2일").defaultTitle("동부 한산 1박2일")
                .durationDays((short) 2)
                .build());

        Course 지난배치 = 샘플코스(preset);
        지난배치.markReady();
        Course 최신배치 = 샘플코스(preset);
        최신배치.markReady();
        샘플코스(preset);   // 아직 GENERATING - 공개 대상 아님

        // 최신배치보다 id가 큰 미끼들 - presetId·courseType 조건이 빠지면 이쪽이 잡혀 실패한다
        CoursePreset 다른프리셋 = presetRepository.save(CoursePreset.builder()
                .code("WEST_2DAYS").name("서부 1박2일").defaultTitle("서부 한산 1박2일")
                .durationDays((short) 2)
                .build());
        샘플코스(다른프리셋).markReady();
        Course 같은프리셋_유저코스 = courseRepository.save(Course.builder()
                .preset(preset)   // courseType은 기본 USER
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .build());
        같은프리셋_유저코스.markReady();
        em.flush();

        assertThat(courseRepository.findFirstByPresetIdAndCourseTypeAndStatusOrderByIdDesc(
                preset.getId(), CourseType.SAMPLE, CourseStatus.READY))
                .hasValueSatisfying(c -> assertThat(c.getId()).isEqualTo(최신배치.getId()));
    }

    private Course 샘플코스(CoursePreset preset) {
        return courseRepository.save(Course.builder()
                .preset(preset).courseType(CourseType.SAMPLE)
                .generationReason(GenerationReason.SAMPLE_BATCH)
                .title(preset.getDefaultTitle())
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .build());
    }

    @Test
    void 비용_팩토리는_정확도_불변식을_지킨다() {
        Course course = 임시코스();

        CourseItemCost 검증가 = CourseItemCost.verified(
                course, null, 42L, CostCategory.FOOD, 16000, "8,000원 × 2명");
        assertThat(검증가.getAccuracyType()).isEqualTo(CostAccuracy.VERIFIED);
        assertThat(검증가.getAmountMin()).isEqualTo(검증가.getAmountMax()).isEqualTo(16000);
        assertThat(검증가.getMenuId()).isEqualTo(42L);

        // 근거 메뉴 없는 검증가 금지 - "검증가" 라벨이 거짓으로 붙는 유일한 구멍
        assertThatThrownBy(() -> CourseItemCost.verified(
                course, null, null, CostCategory.FOOD, 16000, "근거 없음"))
                .isInstanceOf(IllegalArgumentException.class);

        CourseItemCost 추정 = CourseItemCost.estimated(
                course, null, CostCategory.LODGING, 50000, 80000, "동부 숙소 추정 시세");
        assertThat(추정.getAccuracyType()).isEqualTo(CostAccuracy.ESTIMATED);
        assertThat(추정.getAmountMin()).isEqualTo(50000);
        assertThat(추정.getAmountMax()).isEqualTo(80000);

        CourseItemCost 미상 = CourseItemCost.unknown(course, null, CostCategory.ACTIVITY);
        assertThat(미상.getAccuracyType()).isEqualTo(CostAccuracy.UNKNOWN);
        assertThat(미상.getAmountMin()).isNull();
        assertThat(미상.getAmountMax()).isNull();
        assertThat(미상.getBasisText()).isNull();

        assertThatThrownBy(() -> CourseItemCost.estimated(
                course, null, CostCategory.FOOD, 30000, 20000, "뒤집힌 범위"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 회귀 방지: 혼잡 적재 배치는 같은 발표 버전을 벌크 DELETE 후 재적재한다 -
     * 스냅숏 FK가 RESTRICT면 코스 하나 때문에 적재 전체가 죽는다. ON DELETE SET NULL 검증.
     */
    @Test
    void 예보_버전을_재적재해도_스냅숏이_적재를_막지_않는다() {
        Course course = 임시코스();
        CourseItem item = itemRepository.save(CourseItem.builder()
                .course(course).place(혼인지)
                .dayNo((short) 1).position((short) 1)
                .visitDate(출발일)
                .plannedCongestionForecast(혼인지예보)
                .build());
        em.flush();

        int deleted = forecastRepository.deleteVersion(LocalDateTime.of(2026, 9, 10, 0, 0));
        em.clear();

        assertThat(deleted).isEqualTo(1);
        CourseItem loaded = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(loaded.getPlannedCongestionForecast()).isNull();   // '혼잡 정보 없음'으로 정직하게 강등
    }

    @Test
    void 저장_가드는_비정상_전이를_전부_거부한다() {
        User 회원 = 회원가입("guard@hangat.local");
        Course course = 임시코스();

        // GENERATING - 아직 저장할 결과가 없다
        assertThatThrownBy(() -> course.markSaved(회원, "제목"))
                .isInstanceOf(IllegalStateException.class);

        course.markReady();
        assertThatThrownBy(() -> course.markSaved(null, "제목"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> course.markSaved(회원, "  "))
                .isInstanceOf(IllegalArgumentException.class);

        // 정상 저장 후 재호출 - 소유자가 조용히 갈리는 사고 방지
        course.markSaved(회원, "정상 저장");
        User 다른회원 = 회원가입("other@hangat.local");
        assertThatThrownBy(() -> course.markSaved(다른회원, "가로채기"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(course.getUser()).isSameAs(회원);
    }

    @Test
    void 샘플_코스는_회원_소유로_저장할_수_없다() {
        CoursePreset preset = presetRepository.save(CoursePreset.builder()
                .code("GUARD_SAMPLE").name("가드 검증").defaultTitle("샘플")
                .durationDays((short) 2)
                .build());
        Course 샘플 = 샘플코스(preset);
        샘플.markReady();

        assertThatThrownBy(() -> 샘플.markSaved(회원가입("sample@hangat.local"), "샘플 담기"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(샘플.getUser()).isNull();   // 명세서 CHECK: SAMPLE이면 user NULL 유지
    }

    @Test
    void 삭제된_코스는_어떤_전이로도_부활하지_않는다() {
        Course course = 임시코스();
        course.softDelete();
        LocalDateTime 첫삭제시각 = course.getDeletedAt();

        assertThatThrownBy(() -> course.markSaved(회원가입("del@hangat.local"), "부활 시도"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(course::markReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> course.markFailed("LATE_ARRIVAL"))
                .isInstanceOf(IllegalStateException.class);

        course.softDelete();   // 멱등 - 두 번 눌러도 삭제 시각이 안 바뀐다
        assertThat(course.getStatus()).isEqualTo(CourseStatus.DELETED);
        assertThat(course.getDeletedAt()).isEqualTo(첫삭제시각);
    }

    /** 회귀 방지: @OnDelete(SET_NULL)가 빠져 RESTRICT로 돌아가면 이 테스트가 FK 위반으로 잡는다. */
    @Test
    void 소유자가_물리_삭제되면_코스는_남고_소유자만_비워진다() {
        User 회원 = 회원가입("bye@hangat.local");
        Course course = 임시코스();
        course.markReady();
        course.markSaved(회원, "남을 코스");
        em.flush();
        em.clear();

        em.remove(em.find(User.class, 회원.getId()));
        em.flush();
        em.clear();

        Course loaded = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(loaded.getUser()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(CourseStatus.SAVED);   // 코스 자체는 살아 있다
    }

    @Test
    void 코스를_물리_삭제하면_일정과_비용이_함께_지워진다() {
        Course course = 임시코스();
        CourseItem item = 일정(course, 성산일출봉, 1, 1);
        costRepository.save(CourseItemCost.unknown(course, item, CostCategory.ACTIVITY));
        em.flush();
        em.clear();

        courseRepository.deleteById(course.getId());
        em.flush();
        em.clear();

        assertThat(itemRepository.findItemsWithPlace(course.getId())).isEmpty();
        assertThat(costRepository.findByCourseId(course.getId())).isEmpty();
        assertThat(placeRepository.findById(성산일출봉.getId())).isPresent();   // 장소는 RESTRICT - 남는다
    }

    @Test
    void 프리셋을_지워도_샘플_코스는_남는다() {
        CoursePreset preset = presetRepository.save(CoursePreset.builder()
                .code("BYE_PRESET").name("내릴 프리셋").defaultTitle("샘플")
                .durationDays((short) 2)
                .build());
        Course 샘플 = 샘플코스(preset);
        em.flush();
        em.clear();

        presetRepository.deleteById(preset.getId());
        em.flush();
        em.clear();

        assertThat(courseRepository.findById(샘플.getId()).orElseThrow().getPreset()).isNull();
    }

    @Test
    void 비용_재계산은_지우고_다시_쌓는다() {
        Course course = 임시코스();
        costRepository.save(CourseItemCost.unknown(course, null, CostCategory.ACTIVITY));
        costRepository.save(CourseItemCost.verified(course, null, 42L, CostCategory.FOOD, 16000, "8,000원 × 2명"));
        em.flush();

        assertThat(costRepository.deleteByCourse(course.getId())).isEqualTo(2);
        em.clear();
        assertThat(costRepository.findByCourseId(course.getId())).isEmpty();
    }
}
