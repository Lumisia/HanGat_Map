package com.example.hangat.course;

import com.example.hangat.course.facts.*;
import com.example.hangat.course.model.*;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.common.util.DateTimes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.*;

/** Bounded, read-only projection of trusted DB identities. No provider calls or writes. */
@Service
@Transactional(readOnly = true)
public class CourseDbCandidateService {
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private static final String FETCH = "select m from PlaceSourceMapping m join fetch m.place p "
            + "join fetch m.source join fetch p.region r join fetch p.primaryCategory c ";

    public CourseDbCandidateService(EntityManager entityManager, ObjectMapper mapper) {
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    List<CourseCandidateDto> find(CourseRequestDto request) {
        Set<String> regions = new TreeSet<>();
        request.getCourseRegions().stream().filter(Objects::nonNull)
                .map(CourseRegionDto::getCode).filter(Objects::nonNull).forEach(regions::add);
        Set<String> styles = new TreeSet<>();
        request.getCourseStyles().stream().filter(Objects::nonNull)
                .map(CourseStyleDto::getCode).filter(Objects::nonNull).forEach(styles::add);
        List<PlacePreferenceDto> preferences = request.getCoursePlacePreferences() == null
                ? List.of() : request.getCoursePlacePreferences();
        Map<String, PlaceSourceMapping> pool = new LinkedHashMap<>();
        for (PlacePreferenceDto preference : preferences) {
            if (preference == null || preference.getPreferenceType() != PreferenceType.WANT
                    || preference.getSourceCode() == null || preference.getSourcePlaceId() == null) continue;
            entityManager.createQuery(FETCH + "where m.source.code=:source and m.sourcePlaceId=:identity", PlaceSourceMapping.class)
                    .setParameter("source", preference.getSourceCode()).setParameter("identity", preference.getSourcePlaceId())
                    .setMaxResults(1).getResultList().forEach(m -> pool.put(key(m), m));
        }
        // Reuse the pre-existing raw-provider cap, not an unbounded findAll.
        String where = "where m.source.code='KTO' and m.isActive=true "
                + "and p.businessStatus<>com.example.hangat.map.model.enums.BusinessStatus.CLOSED "
                + "and c.code in ('TOURIST','FOOD','CAFE','LODGING') "
                + "and p.latitude between 32.9 and 33.7 and p.longitude between 126.0 and 127.1 ";
        if (!regions.isEmpty()) where += "and r.code in :regions ";
        List<String> avoided = preferences.stream().filter(Objects::nonNull)
                .filter(p -> p.getPreferenceType() == PreferenceType.AVOID && "KTO".equals(p.getSourceCode()))
                .map(PlacePreferenceDto::getSourcePlaceId).filter(Objects::nonNull).toList();
        if (!avoided.isEmpty()) where += "and m.sourcePlaceId not in :avoided ";
        // Fetch a bounded style-specific pool BEFORE the general ID-ordered cap.
        // Otherwise a cafe beyond row 300 is invisible even though it exists in the DB.
        for (String style : styles) {
            String tagPredicate = "pt.tag.code=:style";
            List<String> prefixes = StoredPlaceStyleResolver.prefixes(style);
            for (int i=0;i<prefixes.size();i++) tagPredicate += " or pt.tag.code like :prefix"+i;
            String categoryPredicate = "CAFE".equals(style) ? "c.code='CAFE' or " : "";
            var styleQuery = entityManager.createQuery(FETCH + where + "and ("+categoryPredicate
                    + "exists (select pt.place.id from PlaceTag pt where pt.place=p and pt.tag.isActive=true and ("
                    + tagPredicate + "))) order by m.sourcePlaceId,m.id", PlaceSourceMapping.class);
            styleQuery.setParameter("style",style);
            for(int i=0;i<prefixes.size();i++)styleQuery.setParameter("prefix"+i,prefixes.get(i)+"%");
            if(!regions.isEmpty())styleQuery.setParameter("regions",regions);
            if(!avoided.isEmpty())styleQuery.setParameter("avoided",avoided);
            styleQuery.setMaxResults(CourseCandidateShortlistService.MAX_SHORTLIST_SIZE).getResultList()
                    .forEach(m -> pool.putIfAbsent(key(m),m));
        }
        var query = entityManager.createQuery(FETCH + where + "order by m.sourcePlaceId, m.id", PlaceSourceMapping.class);
        if (!regions.isEmpty()) query.setParameter("regions", regions);
        if (!avoided.isEmpty()) query.setParameter("avoided", avoided);
        query.setMaxResults(TourApiService.MAX_RAW_CANDIDATES).getResultList().forEach(m -> pool.putIfAbsent(key(m), m));
        if (pool.isEmpty()) return List.of();
        List<Long> ids = pool.values().stream().map(m -> m.getPlace().getId()).distinct().toList();
        Map<Long, List<StyleHint>> tags = new HashMap<>();
        if (!styles.isEmpty()) {
            for (Object[] row : entityManager.createQuery("select pt.place.id, pt.tag.code from PlaceTag pt "
                    + "where pt.place.id in :ids and pt.tag.isActive=true order by pt.place.id,pt.tag.code", Object[].class)
                    .setParameter("ids", ids).getResultList()) {
                for(String code : StoredPlaceStyleResolver.resolve(row[1].toString())) {
                    tags.computeIfAbsent((Long) row[0], ignored -> new ArrayList<>())
                            .add(new StyleHint(code, "DB_PLACE_TAG", row[1].toString()));
                }
            }
        }
        List<CourseCandidate> facts = new ArrayList<>();
        for (PlaceSourceMapping mapping : pool.values()) {
            var p = mapping.getPlace();
            PlacePreferenceDto preference = preferences.stream().filter(Objects::nonNull)
                    .filter(x -> key(mapping).equals(x.getSourceCode() + ":" + x.getSourcePlaceId())).findFirst().orElse(null);
            if (preference != null && preference.getPreferenceType() == PreferenceType.AVOID) continue;
            boolean want = preference != null && preference.getPreferenceType() == PreferenceType.WANT;
            List<StyleHint> hints = new ArrayList<>(tags.getOrDefault(p.getId(), List.of()));
            if ("CAFE".equals(p.getPrimaryCategory().getCode()) && hints.stream().noneMatch(h -> "CAFE".equals(h.styleCode())))
                hints.add(new StyleHint("CAFE", "DB_CATEGORY", "CAFE"));
            if (mapping.getRawPayload() != null && mapping.getSource().getCode().equals("KTO")) {
                try {
                    TourPlaceDto raw = mapper.readerFor(TourPlaceDto.class)
                            .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(mapping.getRawPayload());
                    for (String code : TourPlaceStyleHintResolver.resolve(raw)) {
                        if (hints.stream().noneMatch(h -> h.styleCode().equals(code)))
                            hints.add(new StyleHint(code, "KTO", "stored classification"));
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // A malformed optional source payload is not a licence to invent style facts.
                }
            }
            String candidateId = mapping.getSource().getCode().equals("KTO")
                    ? mapping.getSourcePlaceId() : key(mapping);
            facts.add(new CourseCandidate(new CandidateIdentity(candidateId, p.getId(), mapping.getSource().getCode(), mapping.getSourcePlaceId()),
                    new PlaceFact(p.getName(), p.getLotAddress(), p.getRoadAddress(), p.getLatitude(), p.getLongitude(), p.getImageUrl()),
                    want ? UserConstraint.want(preference.getFixedDate(), preference.getFixedTime()) : UserConstraint.none(),
                    p.getRegion().getCode(), List.of(),
                    new InternalPlaceCategory(p.getPrimaryCategory().getId().longValue(), p.getPrimaryCategory().getCode(), p.getPrimaryCategory().getName()),
                    hints, List.of(), null));
        }
        List<CourseCandidate> selected = select(request, facts, styles);
        List<Long> selectedIds = selected.stream().map(f -> f.identity().placeId()).distinct().toList();
        Map<Long, List<CongestionFact>> congestion = new HashMap<>();
        if (!selectedIds.isEmpty()) {
            var start = request.getStartDate().atStartOfDay(DateTimes.KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            var end = request.getEndDate().plusDays(1).atStartOfDay(DateTimes.KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            for (CongestionForecast f : entityManager.createQuery("select f from CongestionForecast f join fetch f.source "
                    + "where f.place.id in :ids and f.baseAt=(select max(v.baseAt) from CongestionForecast v "
                    + "where v.place.id=f.place.id and v.forecastAt=f.forecastAt) "
                    + "and f.forecastAt>=:start and f.forecastAt<:end order by f.place.id,f.forecastAt", CongestionForecast.class)
                    .setParameter("ids", selectedIds).setParameter("start", start).setParameter("end", end).getResultList()) {
                congestion.computeIfAbsent(f.getPlace().getId(), ignored -> new ArrayList<>()).add(new CongestionFact(f.getId(),
                        f.getForecastAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(DateTimes.KST).toLocalDate(),
                        f.getRate(), CongestionLevel.from(f.getRate()), f.getSource().getCode()));
            }
        }
        return selected.stream().map(f -> CourseCandidateDto.fromStored(new CourseCandidate(f.identity(), f.place(),
                f.userConstraint(), f.regionCode(), f.externalClassifications(), f.internalPlaceCategory(), f.styleHints(),
                congestion.getOrDefault(f.identity().placeId(), List.of()), null))).toList();
    }

    static List<CourseCandidate> select(CourseRequestDto request, List<CourseCandidate> facts, Set<String> styles) {
        List<CourseCandidate> wants = facts.stream().filter(f -> f.userConstraint().preferenceType() == PreferenceType.WANT).toList();
        Set<Long> wantedPlaces = wants.stream().map(f -> f.identity().placeId()).collect(java.util.stream.Collectors.toSet());
        List<CourseCandidate> ordinary = facts.stream().filter(f -> f.userConstraint().preferenceType() != PreferenceType.WANT
                && !wantedPlaces.contains(f.identity().placeId())).toList();
        Comparator<CourseCandidate> order = Comparator.comparing((CourseCandidate f) -> f.styleHints().stream().noneMatch(h -> styles.contains(h.styleCode())))
                .thenComparing(f -> f.identity().candidateId());
        boolean proximity = request.getTransport() == Transport.RENTAL_CAR && request.getCourseRegions().size() <= 1 && !ordinary.isEmpty();
        if (proximity) {
            List<CourseCandidate> anchors = wants.stream().filter(f -> f.place().latitude() != null && f.place().longitude() != null).toList();
            if (anchors.isEmpty()) anchors = List.of(ordinary.stream().min(Comparator.comparingDouble((CourseCandidate f) -> ordinary.stream()
                    .mapToDouble(o -> distance(f, o)).sum()).thenComparing(f -> f.identity().candidateId())).orElseThrow());
            final List<CourseCandidate> fixedAnchors = anchors;
            order = Comparator.comparingDouble((CourseCandidate f) -> fixedAnchors.stream().mapToDouble(a -> distance(f,a)).min().orElseThrow())
                    .thenComparing(f -> f.identity().candidateId());
        }
        List<CourseCandidate> result = new ArrayList<>(wants);
        List<CourseCandidate> ranked = ordinary.stream().sorted(order).toList();
        if (!proximity) {
            List<CourseCandidate> diversified = new ArrayList<>();
            for (boolean matched : List.of(true, false)) {
                Map<String, Deque<CourseCandidate>> buckets = new LinkedHashMap<>();
                ranked.stream().filter(f -> f.styleHints().stream().anyMatch(h -> styles.contains(h.styleCode())) == matched)
                        .forEach(f -> buckets.computeIfAbsent(f.regionCode() + "|" + f.internalPlaceCategory().code(),
                                ignored -> new ArrayDeque<>()).add(f));
                while (buckets.values().stream().anyMatch(b -> !b.isEmpty())) {
                    buckets.values().forEach(b -> { if (!b.isEmpty()) diversified.add(b.removeFirst()); });
                }
            }
            ranked = diversified;
        }
        int target = CourseCandidateShortlistService.targetSize(request);
        for (String style : new TreeSet<>(styles)) {
            if(result.size() >= target) break;
            if(result.stream().anyMatch(f -> f.styleHints().stream().anyMatch(h -> style.equals(h.styleCode())))) continue;
            ranked.stream().filter(f -> f.styleHints().stream().anyMatch(h -> style.equals(h.styleCode())))
                    .findFirst().ifPresent(result::add);
        }
        Set<String> selected = result.stream().map(f -> f.identity().candidateId()).collect(java.util.stream.Collectors.toSet());
        ranked.stream().filter(f -> !selected.contains(f.identity().candidateId()))
                .limit(Math.max(0, target-result.size())).forEach(result::add);
        return List.copyOf(result);
    }

    private static double distance(CourseCandidate a, CourseCandidate b) {
        double lat=a.place().latitude().doubleValue(), lon=a.place().longitude().doubleValue();
        double blat=b.place().latitude().doubleValue(), blon=b.place().longitude().doubleValue();
        double d=Math.pow(Math.sin(Math.toRadians(lat-blat)/2),2)+Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(blat))*Math.pow(Math.sin(Math.toRadians(lon-blon)/2),2);
        return 2*Math.asin(Math.sqrt(Math.min(1,d)));
    }

    private static String key(PlaceSourceMapping mapping) {
        return mapping.getSource().getCode()+":"+mapping.getSourcePlaceId();
    }
}
