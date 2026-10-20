package com.example.hangat.batch;

import com.example.hangat.course.service.SampleCourseGenerator;
import com.example.hangat.domain.weather.TripWeatherIngestService;
import com.example.hangat.domain.weather.WeatherIngestService;
import com.example.hangat.map.congestion.CongestionIngestService;
import com.example.hangat.map.hiddengem.HiddenGemScoringService;
import com.example.hangat.map.goodprice.GoodPriceIngestService;
import com.example.hangat.map.place.PlaceIngestService;
import com.example.hangat.map.detail.OverviewIngestService;
import com.example.hangat.map.store.StoreIngestService;
import com.example.hangat.notification.service.trip.TripNotificationJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Kubernetes Job용 단발성 실행기 - batch 프로필에서 지정한 작업만 한 번 실행한다.
 * 예외와 불완전한 적재 결과를 기동 실패로 전달해 실패한 Job이 성공으로 기록되지 않게 한다.
 */
@Slf4j
@Component
@Profile("batch")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${hangat.batch.job:}' != 'media-cleanup' && '${hangat.batch.job:}' != 'account-cleanup' && '${hangat.batch.job:}' != 'account-media-cleanup'")
public class BatchJobRunner implements ApplicationRunner {
    private final String job;
    private final CongestionIngestService congestion;
    private final WeatherIngestService weather;
    private final SampleCourseGenerator courses;
    private final BatchPrerequisiteChecker prerequisites;
    private final TripWeatherIngestService tripWeather;
    private final TripNotificationJobService tripNotifications;
    private final PlaceIngestService places;
    private final StoreIngestService stores;
    private final GoodPriceIngestService goodPrice;
    private final OverviewIngestService overviews;
    private final HiddenGemScoringService hiddenGems;

    public BatchJobRunner(@Value("${hangat.batch.job:}") String job,
                          CongestionIngestService congestion, WeatherIngestService weather,
                          SampleCourseGenerator courses, BatchPrerequisiteChecker prerequisites,
                          TripWeatherIngestService tripWeather, TripNotificationJobService tripNotifications,
                          PlaceIngestService places, StoreIngestService stores, GoodPriceIngestService goodPrice,
                          OverviewIngestService overviews, HiddenGemScoringService hiddenGems) {
        this.job = job;
        this.congestion = congestion;
        this.weather = weather;
        this.courses = courses;
        this.prerequisites = prerequisites;
        this.tripWeather = tripWeather;
        this.tripNotifications = tripNotifications;
        this.places = places;
        this.stores = stores;
        this.goodPrice = goodPrice;
        this.overviews = overviews;
        this.hiddenGems = hiddenGems;
    }

    /** 기존 스케줄러의 예외 흡수·재시도는 사용하지 않는다. 재시도 횟수는 Job이 관리한다. */
    @Override
    public void run(ApplicationArguments args) {
        log.info("배치 시작 job={}", job);
        switch (job) {
            case "congestion" -> {
                var result = congestion.ingest();
                if (result.saved() == 0) {
                    throw new IllegalStateException("혼잡도 적재 실패: 저장된 예보가 없습니다.");
                }
                // 숨은 명소는 "집계 대상이 아닌 관광지"라 최신 발표분이 확정된 직후 판정한다.
                // 알림 처리보다 먼저 - 둘은 독립이고, 알림이 회원 한 명 실패로 예외를 내도 판정이 밀리면 안 된다.
                // 판정 실패는 혼잡 적재를 되돌릴 이유가 아니다 - 재시도하면 집중률 API를 다시 부른다. 로그로 남긴다.
                try {
                    log.info("숨은 명소 판정 결과 {}", hiddenGems.score());
                } catch (RuntimeException e) {
                    log.error("숨은 명소 판정 실패 - 어제 판정이 남아 있고 다음 배치가 다시 시도한다", e);
                }
                tripNotifications.run("congestion");

                log.info("혼잡도 배치 결과 {}", result);
            }
            // ────────────────────────── 기존 일별 날씨 적재 ──────────────────────────
            case "weather" -> {
                var result = weather.ingest();

                // 새로 적재된 중기예보 등의 변화도 비교한다.
                // 비교는 DB 조회이며, 기상청을 추가로 호출하지 않는다.
                tripNotifications.run("weather");

                if (!result.hasCompleteShortTermCoverage()
                        || result.midRows() == 0
                        || result.midFailed()) {
                    throw new IllegalStateException(
                            "일별 날씨 적재 불완전: " + result
                    );
                }

                log.info("일별 날씨 배치 결과 {}", result);
            }

            // ────────────────────────── 알림용 최신 시간별 예보 ──────────────────────────
            case "trip-weather" -> {
                int failedRegions = tripWeather.ingest();

                // 성공적으로 저장된 권역의 최신 예보를 비교한다.
                // 실패한 권역의 누락 값을 맑음으로 간주하지 않는다.
                tripNotifications.run("weather");

                if (failedRegions > 0) {
                    throw new IllegalStateException(
                            "알림용 날씨 적재 불완전: 실패 권역=" + failedRegions
                    );
                }

                log.info("알림용 날씨 수집·비교 완료");
            }
            case "sample-courses" -> {
                var startDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
                prerequisites.check(startDate);
                var result = courses.generate(startDate);
                if (!result.isComplete()) {
                    throw new IllegalStateException("샘플 코스 생성 불완전: " + result);
                }
                log.info("샘플 코스 배치 결과 {}", result);
            }
            case "trip-reminders" -> tripNotifications.run("reminders");
            // ────────────────────────── 장소 원천 재적재 + 출석 체크(폐업 판정) ──────────────────────────
            case "places" -> {
                // 순서 고정: KTO(관광지) → SBIZ(상가) → 착한가격(앞의 둘 위에 플래그만 얹는다)
                var ktoResult = places.ingest();
                var sbizResult = stores.ingest();
                var goodPriceResult = goodPrice.ingest();
                // 수신이 부족해 판정을 건너뛴 날은 실패로 남긴다 - 조용히 성공 처리되면 아무도 안 본다
                if (ktoResult.presence().skipped() || sbizResult.presence().skipped() || goodPriceResult.clearSkipped()) {
                    throw new IllegalStateException("장소 재적재 불완전(수신 부족으로 판정 보류): KTO=" + ktoResult
                            + " SBIZ=" + sbizResult + " 착한가격=" + goodPriceResult);
                }
                log.info("장소 재적재 결과 KTO={} SBIZ={} 착한가격={}", ktoResult, sbizResult, goodPriceResult);
                // 새로 들어온 관광지의 소개글을 같은 새벽에 채운다(detailCommon2, 우리만 쓰는 오퍼레이션이라 1,000/일 여유).
                // 첫 배포 땐 812곳이 첫날 다 채워지고, 이후엔 신규 몇 건뿐. 소개글이 없는 건 실패가 아니라 결과만 남긴다
                var overviewResult = overviews.ingest(OverviewIngestService.DEFAULT_LIMIT);
                log.info("관광지 소개글 적재 결과 {}", overviewResult);
            }

            default -> throw new IllegalArgumentException(
                    "hangat.batch.job은 congestion, weather, trip-weather, "
                            + "sample-courses, trip-reminders, places 중 하나여야 합니다."
            );
        }
        log.info("배치 완료 job={}", job);
    }
}
