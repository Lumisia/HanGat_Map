package com.example.hangat.course.share;

import com.example.hangat.config.security.jwt.JwtProvider;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.map.model.entity.*;
import com.example.hangat.user.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CourseShareApiTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired CourseRepository courses;
    @Autowired CourseItemRepository items;
    private User owner;
    private User stranger;
    private Course course;
    private Place first;

    @BeforeEach void seed() {
        owner = User.signUpWithSocial("share-owner@hangat.local", "공유 주인");
        stranger = User.signUpWithSocial("share-other@hangat.local", "다른 사용자");
        em.persist(owner); em.persist(stranger);
        course = courses.save(Course.builder().startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 1)).transport(Transport.RENTAL_CAR)
                .people((short) 3).estimatedCostMin(888888).build());
        course.markReady(); course.markSaved(owner, "제주 하루 여행");
        Region region = Region.builder().code("SHARE").name("공유 지역").displayOrder((byte) 9).build();
        PlaceCategory category = PlaceCategory.builder().code("SHARE").name("공유 관광지").build();
        em.persist(region); em.persist(category);
        first = Place.builder().name("첫 관광지").normalizedName("첫 관광지").region(region).primaryCategory(category)
                .latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.4")).build();
        em.persist(first);
        for (int i = 1; i <= 2; i++) {
            items.save(CourseItem.builder().course(course).place(first).dayNo((short) 1).position((short) i)
                    .visitDate(course.getStartDate()).startTime(LocalTime.of(9 + i, 0)).endTime(LocalTime.of(10 + i, 0))
                    .inboundDistanceM(1500).inboundTravelMinutes((short) 10)
                    .recommendationReason("비공개 예산과 숙소 조건").build());
        }
        em.flush();
    }
    private String auth(User user) { return "Bearer " + jwt.createAccessToken(user.getId()); }
    private String endpoint() { return "/courses/" + course.getId() + "/share"; }
    private String create() throws Exception {
        String body = mvc.perform(post(endpoint()).header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.active").value(true))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("result").path("token").asText();
    }
    private void unavailable(String token) throws Exception {
        mvc.perform(get("/shared-courses/" + token)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3310))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void openingStatusDoesNotPublishAndMutationRequiresOwner() throws Exception {
        mvc.perform(get(endpoint()).header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.active").value(false))
                .andExpect(jsonPath("$.result.token").doesNotExist());
        for (var method : java.util.List.of(get(endpoint()), post(endpoint()), delete(endpoint()))) {
            mvc.perform(method).andExpect(status().isUnauthorized());
        }
        for (var method : java.util.List.of(get(endpoint()), post(endpoint()), delete(endpoint()))) {
            mvc.perform(method.header("Authorization", auth(stranger)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3307));
        }
    }

    @Test void publicPayloadIsAllowlistedAndDoesNotGrantOwnerAccess() throws Exception {
        String token = create();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        var response = mvc.perform(get("/shared-courses/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.result.title").value("제주 하루 여행"))
                .andExpect(jsonPath("$.result.days[0].items[0].place_id").value(first.getId()))
                .andExpect(jsonPath("$.result.days[0].items[0].inbound_distance_m").doesNotExist())
                .andExpect(jsonPath("$.result.days[0].items[1].inbound_distance_m").value(1500))
                .andReturn().getResponse().getContentAsString();
        JsonNode value = json.readTree(response).path("result");
        assertThat(value.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("title", "start_date", "end_date", "transport", "days");
        assertThat(response).doesNotContain("999999", "888888", "share-owner", "비공개", "recommendation_reason", "accommodation", "people", "manageable", "swappable");
        mvc.perform(get("/courses/" + course.getId())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3307));
    }

    @Test void duplicateCreateReusesActiveLinkAndRestartRotatesIt() throws Exception {
        String original = create();
        assertThat(create()).isEqualTo(original);
        mvc.perform(delete(endpoint()).header("Authorization", auth(owner))).andExpect(status().isOk());
        unavailable(original);
        mvc.perform(delete(endpoint()).header("Authorization", auth(owner))).andExpect(status().isOk());
        String next = create();
        assertThat(next).isNotEqualTo(original);
        unavailable(original);
        mvc.perform(get("/shared-courses/" + next)).andExpect(status().isOk());
    }

    @Test void titleChangesAppearWithoutNewLinkAndDeletionRevokesIt() throws Exception {
        String token = create();
        course.rename("변경한 일정"); em.flush();
        mvc.perform(get("/shared-courses/" + token)).andExpect(jsonPath("$.result.title").value("변경한 일정"));
        mvc.perform(delete("/courses/" + course.getId()).header("Authorization", auth(owner))).andExpect(status().isOk());
        unavailable(token);
        mvc.perform(post(endpoint()).header("Authorization", auth(owner))).andExpect(status().isBadRequest());
    }

    @Test void withdrawnOwnerRevokesPublicAccess() throws Exception {
        String token = create();
        owner.withdraw(); em.flush();
        unavailable(token);
    }

    @Test void malformedAndMissingTokensReturnSameNotFound() throws Exception {
        unavailable("not-a-token");
        unavailable("a".repeat(43));
    }

    @Test void otherOwnerCannotDisableExistingLink() throws Exception {
        String token = create();
        mvc.perform(delete(endpoint()).header("Authorization", auth(stranger)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/shared-courses/" + token)).andExpect(status().isOk());
    }

    @Test void readyAndOwnerlessCoursesCannotBePublished() throws Exception {
        Course ready = courses.save(Course.builder().user(owner).startDate(course.getStartDate())
                .endDate(course.getEndDate()).transport(Transport.RENTAL_CAR).build());
        ready.markReady(); em.flush();
        mvc.perform(post("/courses/" + ready.getId() + "/share").header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3307));
        Course guest = courses.save(Course.builder().startDate(course.getStartDate())
                .endDate(course.getEndDate()).transport(Transport.RENTAL_CAR).build());
        guest.markReady(); em.flush();
        mvc.perform(post("/courses/" + guest.getId() + "/share").header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3307));
    }
}
