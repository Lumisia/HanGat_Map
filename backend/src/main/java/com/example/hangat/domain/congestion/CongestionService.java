package com.example.hangat.domain.congestion;

import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 혼잡 공통 코어 - 메인 추천·코스 생성·대안 스왑이 모두 이 서비스를 통해 등급을 본다.
 * 임계값은 휴리스틱(캘리브레이션 예정)이므로 반드시 아래 상수로만 관리한다.
 *
 * <p><b>2026-08-24 통합</b>: 데이터 계층을 {@code map} 도메인으로 옮겼다.
 * 원래 이 패키지에 {@code Place}·{@code CongestionForecast}가 따로 있었는데,
 * 지도 담당이 명세서(9.0/16.0) 기준으로 만든 것과 <b>엔티티 이름이 같아 빈 등록이 충돌</b>했다.
 * 등급 변환(이 클래스의 본체)은 그대로 두고 조회만 갈아끼운 것이라 호출부는 영향이 없다.
 */
@Service
@Transactional(readOnly = true)
public class CongestionService {

    private final CongestionForecastRepository repository;

    public CongestionService(CongestionForecastRepository repository) {
        this.repository = repository;
    }

    /**
     * 등급 변환 - 팀 표준 3단계(한산 <40 / 보통 <70 / 혼잡 >=70)로 통일(2026-08-31).
     * 임계값의 단일 출처는 map의 {@link CongestionLevel#from} 하나다 - DB에 영속되는 값과
     * 화면 등급이 같은 함수에서 나와야 저장 스냅숏과 실시간 표시가 어긋나지 않는다.
     * 프론트 utils/congestion.ts도 같은 3단계를 유지해야 한다.
     */
    public CongestionLevel levelOf(double rate) {
        return CongestionLevel.from(BigDecimal.valueOf(rate));
    }

    /**
     * 그날 예보 전체를 placeId → 집중률 맵으로 (예보 없는 날짜면 빈 맵).
     *
     * <p>두 가지를 맞춰 준다.
     * <ul>
     *   <li><b>발표 버전 고정</b>: 예보는 덮어쓰지 않고 발표할 때마다 쌓이므로(명세서 16.0),
     *       최신 {@code base_at}만 골라야 한 장소가 두 번 나오지 않는다</li>
     *   <li><b>시각 변환</b>: {@code forecast_at}은 UTC로 저장돼 있다. 제주 기준 날짜를
     *       그대로 넘기면 9시간 어긋나 하루가 통째로 빈다</li>
     * </ul>
     */
    public Map<Long, Double> ratesFor(LocalDate date) {
        Optional<LocalDateTime> latestBase = repository.findLatestBaseAt();
        if (latestBase.isEmpty()) {
            return Map.of();
        }
        return repository.findByBaseAtAndForecastAt(latestBase.get(), PlaceNameNormalizer.jejuDayToUtc(date))
                .stream()
                .collect(Collectors.toMap(
                        forecast -> forecast.getPlace().getId(),
                        forecast -> forecast.getRate().doubleValue(),
                        (a, b) -> a));
    }

    /**
     * {@link #ratesFor}의 엔티티 버전 - placeId → 예보 행.
     *
     * <p>숫자만으로 충분한 화면 조회와 달리, 코스 스냅숏(course_items의
     * planned_congestion_forecast_id)은 <b>어느 발표 버전을 보고 골랐는지</b>를 행으로
     * 고정해야 해서 엔티티가 필요하다. 샘플 코스 배치가 쓰고, 스왑 API도 이걸 쓸 예정이다.
     */
    /**
     * 한 장소·한 날짜의 최신 발표 예보 - 스왑이 교체 장소 스냅숏을 박을 때 쓴다.
     * 예보가 없는 장소·날짜면 빈 값이고, 그때 스냅숏은 NULL('혼잡 정보 없음')로 남는다.
     */
    public Optional<com.example.hangat.map.model.entity.CongestionForecast> forecastOf(
            Long placeId, LocalDate date) {
        return repository.findLatestBaseAt()
                .flatMap(baseAt -> repository.findOne(
                        placeId, PlaceNameNormalizer.jejuDayToUtc(date), baseAt));
    }

    public Map<Long, com.example.hangat.map.model.entity.CongestionForecast> forecastsFor(LocalDate date) {
        Optional<LocalDateTime> latestBase = repository.findLatestBaseAt();
        if (latestBase.isEmpty()) {
            return Map.of();
        }
        return repository.findByBaseAtAndForecastAt(latestBase.get(), PlaceNameNormalizer.jejuDayToUtc(date))
                .stream()
                .collect(Collectors.toMap(
                        forecast -> forecast.getPlace().getId(),
                        forecast -> forecast,
                        (a, b) -> a));
    }
}
