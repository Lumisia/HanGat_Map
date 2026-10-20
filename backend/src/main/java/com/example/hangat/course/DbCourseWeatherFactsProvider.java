package com.example.hangat.course;

import com.example.hangat.course.facts.*;
import com.example.hangat.course.model.*;
import com.example.hangat.course.weather.*;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** DB only: at most one forecast read per actual candidate region. No live fallback. */
@Component
public class DbCourseWeatherFactsProvider implements CourseWeatherFactsProvider {
    private final WeatherForecastRepository forecasts;
    private final RegionRepository regions;
    private final Clock clock;
    // Same published-base freshness contract as the team's WeatherService (36 hours).
    static final Duration STALE_AFTER = Duration.ofHours(36);
    @Autowired
    public DbCourseWeatherFactsProvider(WeatherForecastRepository forecasts, RegionRepository regions) {
        this(forecasts, regions, Clock.systemUTC());
    }
    DbCourseWeatherFactsProvider(WeatherForecastRepository forecasts, RegionRepository regions, Clock clock) {
        this.forecasts = forecasts; this.regions = regions; this.clock = clock;
    }
    @Override
    public CourseWeatherFacts load(CourseRequestDto request, List<CourseCandidateDto> candidates) {
        var normalized = new CourseCandidateNormalizer().normalize(request, candidates).candidates();
        var references = new LinkedHashMap<String, String>();
        var weather = loadDates(request.getStartDate(), request.getEndDate(), normalized.stream()
                .map(c -> c.regionCode()).collect(java.util.stream.Collectors.toSet()));
        for (var set : weather.weatherFactSets()) {
            var code = set.facts().get(0).dailyEvidence().regionCode();
            normalized.stream().filter(c -> code.equals(c.regionCode()))
                    .forEach(c -> references.put(c.identity().candidateId(), set.weatherFactSetId()));
        }
        return new CourseWeatherFacts(references, weather.weatherFactSets());
    }

    /** Suspend the detail transaction: an optional weather failure must not mark it rollback-only. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public CourseWeatherFacts loadDates(LocalDate start, LocalDate end, Set<String> regionCodes) {
        var sets = new ArrayList<WeatherFactSet>();
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            for (var region : regions.findAll()) {
                if (!regionCodes.contains(region.getCode()) || region.getKmaGridX() == null || region.getKmaGridY() == null) continue;
                var facts = new ArrayList<WeatherFact>();
                LocalDateTime latestBase = null;
                for (var f : forecasts.findLatestPerDate(region.getId(), PlaceNameNormalizer.jejuDayToUtc(start),
                        PlaceNameNormalizer.jejuDayToUtc(end), WeatherGranularity.DAILY)) {
                    var date = PlaceNameNormalizer.utcToJejuDay(f.getForecastAt());
                    if (date.isBefore(start) || date.isAfter(end) || f.getBaseAt().isBefore(now.minus(STALE_AFTER))
                            || f.getBaseAt().isAfter(now) || !region.getId().equals(f.getRegion().getId())) continue;
                    String source = f.getSource().getCode();
                    if (!Set.of("KMA_SHORT", "KMA_MID").contains(source)) continue;
                    if (f.getSkyCode() == null && f.getTempMin() == null && f.getTempMax() == null && f.getRainProbability() == null) continue;
                    facts.add(new WeatherFact(f.getId(), date, null, null, f.rainProbabilityPercent(),
                            f.getPrecipitationType() == null ? null : f.getPrecipitationType().name(), f.getSkyCode(), null, null,
                            new DailyWeatherEvidence(source, region.getCode(), source.equals("KMA_MID") ? "JEJU_ISLAND" : "REGION",
                                    "DAILY", f.getBaseAt(), f.getTempMin(), f.getTempMax())));
                    if (latestBase == null || latestBase.isBefore(f.getBaseAt())) latestBase = f.getBaseAt();
                }
                if (facts.isEmpty()) continue;
                String id = "db-weather-" + region.getCode();
                var issued = latestBase.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Seoul"));
                sets.add(new WeatherFactSet(id, "KMA", region.getKmaGridX().intValue(), region.getKmaGridY().intValue(),
                        issued.toLocalDate(), issued.toLocalTime(), facts));
            }
        } catch (org.springframework.dao.DataAccessException unavailable) {
            // Optional evidence must not turn a weather storage outage into generation failure.
            return CourseWeatherFacts.empty();
        }
        return new CourseWeatherFacts(Map.of(), sets);
    }
}
