package com.example.hangat.favorite;

import com.example.hangat.config.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 찜 API 경계 - 비로그인 401, 로그인은 JWT 의 회원 ID로 통과. 빈 H2 라 장소는 없다(3201 경로 확인용).
 * 실제 JWT 를 만들어 보낸다 - spring-security-test 가 의존성에 없어 필터를 그대로 태우는 편이 정직하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class FavoriteControllerTest {

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void activeAccount() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=1", Integer.class) == 0) {
            jdbc.update("INSERT INTO users(id,email,nickname,status,auth_version,created_at,updated_at) VALUES (1,'favorite-boundary@example.com','찜회원','ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
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
    void anonymousCannotReadFavorites() throws Exception {
        mockMvc.perform(get("/favorites")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/favorites/ids")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotToggleFavorites() throws Exception {
        mockMvc.perform(put("/favorites/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/favorites/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void memberGetsEmptyIdsAndList() throws Exception {
        mockMvc.perform(get("/favorites/ids").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").isArray())
                .andExpect(jsonPath("$.result").isEmpty());
        mockMvc.perform(get("/favorites").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void memberAddingUnknownPlaceGets3201() throws Exception {
        mockMvc.perform(put("/favorites/999999").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3201));
    }

    @Test
    void memberRemovingNeverFavoritedPlaceStillSucceeds() throws Exception {
        mockMvc.perform(delete("/favorites/999999").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.placeId").value(999999))
                .andExpect(jsonPath("$.result.favorited").value(false));
    }
}
