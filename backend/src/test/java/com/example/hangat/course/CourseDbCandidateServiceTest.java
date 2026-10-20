package com.example.hangat.course;

import com.example.hangat.course.model.*;
import com.example.hangat.map.model.entity.*;
import com.example.hangat.map.model.entity.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CourseDbCandidateServiceTest {
    @Autowired TestEntityManager em;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    Region region; PlaceCategory category; DataSource source;
    @BeforeEach void setup() {
        region=em.persist(Region.builder().code("DB_TEST").name("DB region").displayOrder((byte)99).build());
        category=em.getEntityManager().createQuery("from PlaceCategory where code='TOURIST'",PlaceCategory.class).getResultStream()
                .findFirst().orElseGet(()->em.persist(PlaceCategory.builder().code("TOURIST").name("Tourist").build()));
        source=em.find(DataSource.class,"KTO");
        if(source==null)source=em.persist(DataSource.builder().code("KTO").displayName("DB KTO").providerName("KTO")
                .attributionText("KTO").displayOrder((short)1).isActive(true).build());
    }
    CourseRequestDto request(String extra)throws Exception {
        return mapper.readValue("""
            {"start_date":"2026-09-07","end_date":"2026-09-09","people":2,"transport":"PUBLIC_TRANSIT","course_regions":[{"code":"DB_TEST"}],"course_styles":[{"code":"NATURE"}]
            """+extra+"}",CourseRequestDto.class);
    }
    PlaceSourceMapping place(String identity,String name,String lat,Region r) {
        Place p=em.persist(Place.builder().name(name).normalizedName(name).region(r).primaryCategory(category)
                .latitude(lat==null?null:new BigDecimal(lat)).longitude(new BigDecimal("126.6")).imageUrl("https://example.org/image.jpg").build());
        return em.persist(PlaceSourceMapping.builder().place(p).source(source).sourcePlaceId(identity)
                .rawPayload("{\"cat1\":\"A01\",\"extraProviderField\":true}").build());
    }
    @Test void filtersCoordinatesAndRegionWithoutMergingSameNames()throws Exception {
        var a=place("DB001","same","33.4",region);var b=place("DB002","same","33.5",region);
        place("DB003","null",null,region);place("DB004","outside","37.5",region);
        var other=em.persist(Region.builder().code("DB_OTHER").name("Other region").displayOrder((byte)98).build());
        place("DB005","other","33.4",other);em.flush();em.clear();
        var result=new CourseDbCandidateService(em.getEntityManager(),mapper).find(request(""));
        assertThat(result).extracting(c->c.getStoredCandidate().identity().sourcePlaceId()).containsExactly("DB001","DB002");
        assertThat(result).extracting(c->c.getStoredCandidate().identity().placeId()).containsExactly(a.getPlace().getId(),b.getPlace().getId());
        assertThat(result.get(0).getStoredCandidate().styleHints()).extracting(h->h.styleCode()).contains("NATURE");
        assertThat(result.get(0).getStoredCandidate().congestionFacts()).isEmpty();
    }
    @Test void usesStoredTourismTagsWithoutRawPayloadForEveryStyle() throws Exception {
        String[][] cases = {{"NA010200","NATURE"},{"EX010100","LOCAL"},{"FD050100","CAFE"},
                {"LS010300","ACTIVITY"},{"VE030300","WITH_KIDS"},{"NA020800","PHOTO"}};
        for (int i=0;i<cases.length;i++) {
            Place p=em.persist(Place.builder().name("tagged"+i).normalizedName("tagged"+i).region(region)
                    .primaryCategory(category).latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.6")).build());
            em.persist(PlaceSourceMapping.builder().place(p).source(source).sourcePlaceId("TAG"+i).build());
            Tag tag=em.persist(Tag.builder().code(cases[i][0]).name("test tag"+i).build());
            em.persist(PlaceTag.fromApi(p,tag));
        }
        em.flush();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(request(""));
        var styles=tree.putArray("course_styles");
        for(var c:cases)styles.addObject().put("code",c[1]);
        var req=mapper.treeToValue(tree,CourseRequestDto.class);
        var result=new CourseDbCandidateService(em.getEntityManager(),mapper).find(req);
        for(int i=0;i<cases.length;i++) {
            String id="TAG"+i;
            assertThat(result.stream().filter(c->id.equals(c.getStoredCandidate().identity().candidateId()))
                    .findFirst().orElseThrow().getStoredCandidate().styleHints())
                    .extracting(h->h.styleCode()).contains(cases[i][1]);
        }
    }
    @Test void reservesCafeBeyondRawLimitEvenForRentalCar() throws Exception {
        for(int i=0;i<305;i++)place("A"+String.format("%04d",i),"ordinary"+i,"33.4",region);
        Place p=em.persist(Place.builder().name("cafe").normalizedName("cafe").region(region)
                .primaryCategory(category).latitude(new BigDecimal("33.6")).longitude(new BigDecimal("126.6")).build());
        em.persist(PlaceSourceMapping.builder().place(p).source(source).sourcePlaceId("ZCAFE").build());
        Tag tag=em.persist(Tag.builder().code("FD050100").name("test cafe").build());
        em.persist(PlaceTag.fromApi(p,tag));em.flush();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(request(""));
        tree.put("transport","RENTAL_CAR");tree.putArray("course_styles").addObject().put("code","CAFE");
        var req=mapper.treeToValue(tree,CourseRequestDto.class);
        var result=new CourseDbCandidateService(em.getEntityManager(),mapper).find(req);
        assertThat(result).hasSize(15);
        assertThat(result).extracting(c->c.getStoredCandidate().identity().candidateId()).contains("ZCAFE");
    }
    @Test void wantUsesExactIdentityAndStoredFactsAndFixedSchedule()throws Exception {
        var p=place("DB_WANT","stored name","33.4",region);place("DB_OTHER_ID","stored name","33.5",region);em.flush();
        var req=request(",\"course_place_preferences\":[{\"source_code\":\"KTO\",\"source_place_id\":\"DB_WANT\",\"place_name\":\"untrusted\",\"preference_type\":\"WANT\",\"fixed_date\":\"2026-09-08\",\"fixed_time\":\"14:00:00\"}]");
        var result=new CourseDbCandidateService(em.getEntityManager(),mapper).find(req);
        var fact=result.get(0).getStoredCandidate();
        assertThat(fact.identity().placeId()).isEqualTo(p.getPlace().getId());
        assertThat(fact.place().name()).isEqualTo("stored name");
        assertThat(fact.userConstraint().fixedDate()).hasToString("2026-09-08");
        assertThat(fact.userConstraint().fixedTime()).hasToString("14:00");
        var normalized=new CourseCandidateNormalizer().normalize(req,result);
        assertThat(normalized.candidates()).hasSize(2);
        assertThat(normalized.candidateIdsByPreference()).containsValue("DB_WANT");
    }
    @Test void diversifiesCategoriesWithoutDiscardingSelectedStyle()throws Exception {
        for(int i=0;i<20;i++)place("DB_A"+i,"tourist"+i,"33.4",region);
        category=em.getEntityManager().createQuery("from PlaceCategory where code='CAFE'",PlaceCategory.class)
                .getResultStream().findFirst().orElseGet(()->em.persist(PlaceCategory.builder().code("CAFE").name("Cafe").build()));
        place("DB_ZCAFE","cafe","33.4",region);em.flush();
        var result=new CourseDbCandidateService(em.getEntityManager(),mapper).find(request(""));
        assertThat(result).hasSize(15);
        assertThat(result.get(1).getStoredCandidate().internalPlaceCategory().code()).isEqualTo("CAFE");
    }
    @Test void queryAndShortlistAreBoundedAndDeterministic()throws Exception {
        for(int i=0;i<310;i++)place("DB_LIMIT_"+String.format("%04d",i),"place"+i,"33.4",region);
        em.flush();em.clear();var service=new CourseDbCandidateService(em.getEntityManager(),mapper);
        var first=service.find(request(""));var second=service.find(request(""));
        assertThat(first).hasSize(CourseCandidateShortlistService.targetSize(request("")));
        assertThat(first).extracting(c->c.getStoredCandidate()).containsExactlyElementsOf(second.stream().map(CourseCandidateDto::getStoredCandidate).toList());
        assertThat(first).allMatch(c->c.getStoredCandidate().identity().sourcePlaceId().compareTo("DB_LIMIT_0300")<0);
    }
    @Test void selectsLatestForecastPerPlaceAndTargetTimeInsteadOfGlobalBaseAt()throws Exception {
        var first=place("DB_FORECAST_A","first","33.4",region);
        var second=place("DB_FORECAST_B","second","33.5",region);
        LocalDateTime target=LocalDateTime.of(2026,9,6,15,0);
        em.persist(CongestionForecast.of(first.getPlace(),source,target,
                LocalDateTime.of(2026,9,7,0,0),new BigDecimal("25.00")));
        em.persist(CongestionForecast.of(second.getPlace(),source,target,
                LocalDateTime.of(2026,9,6,0,0),new BigDecimal("55.00")));
        em.flush();em.clear();

        var result=new CourseDbCandidateService(em.getEntityManager(),mapper).find(request(""));

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(candidate ->
                assertThat(candidate.getStoredCandidate().congestionFacts()).hasSize(1));
        assertThat(result).extracting(candidate -> candidate.getStoredCandidate().congestionFacts().get(0).rate())
                .containsExactly(new BigDecimal("25.00"),new BigDecimal("55.00"));
    }
}
