package com.example.hangat.review;

import com.example.hangat.config.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 리뷰 목록 API 경계 - 비로그인 401, 페이징 파라미터 범위, 계정 확인.
 * 찜 쪽 {@code FavoriteControllerTest} 와 같이 실제 JWT 를 만들어 필터를 그대로 태운다
 * (spring-security-test 가 의존성에 없다). H2 는 비어 있어 그 회원은 존재하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class MyReviewControllerTest {

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void activeAccount() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=1", Integer.class) == 0) {
            jdbc.update("INSERT INTO users(id,email,nickname,status,auth_version,created_at,updated_at) VALUES (1,'review-boundary@example.com','경계회원','ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    private String bearer() {
        return "Bearer " + jwtProvider.createAccessToken(1L);
    }

    @Test
    void anonymousCannotReadMyReviews() throws Exception {
        mockMvc.perform(get("/users/me/reviews"))
                .andExpect(status().isUnauthorized());
    }

    /** 남의 목록을 볼 경로가 없다 - 회원 ID 를 URL 로 받지 않는다. */
    @Test
    void thereIsNoPathForSomeoneElsesReviews() throws Exception {
        mockMvc.perform(get("/users/2/reviews").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sizeOverTheCapIsRejectedBeforeTheQuery() throws Exception {
        mockMvc.perform(get("/users/me/reviews")
                        .param("size", "51")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000));
    }

    @Test
    void negativePageIsRejectedBeforeTheQuery() throws Exception {
        mockMvc.perform(get("/users/me/reviews")
                        .param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000));
    }

    /*
      정렬 화이트리스트는 계정 확인 뒤에 보므로 빈 H2 로는 여기까지 닿지 않는다 -
      MyReviewServiceTest.unknownSortIsRejectedBeforeQuery 가 담당한다.
     */

    /**
     * 토큰은 유효하지만 그 회원이 DB 에 없거나 ACTIVE 가 아니면 목록을 주지 않는다.
     * 필터에서 유효하지 않은 세션으로 차단한다.
     */
    @Test
    void tokenWithoutAnActiveAccountGetsNoList() throws Exception {
        mockMvc.perform(get("/users/me/reviews").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(Long.MAX_VALUE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3002));
    }
}
