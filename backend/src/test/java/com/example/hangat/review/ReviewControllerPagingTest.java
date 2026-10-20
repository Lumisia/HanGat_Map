package com.example.hangat.review;

import com.example.hangat.common.model.PageResponse;
import com.example.hangat.review.model.ReviewResponse;
import com.example.hangat.review.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 후기 목록 페이징 경계값 - 잘못된 page·size 는 서비스에 닿기 전에 400 봉투로 거절돼야 한다.
 * 검증 전에는 size=0·page=-1 이 PageRequest 예외로 봉투 밖 500 이었고 size 상한이 없었다(실측 2026-09-07).
 * 서비스는 목으로 두어 장소 존재 여부(3201)와 검증(3000)을 분리해 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewControllerPagingTest {

    private static final String URL = "/places/1/reviews";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReviewService reviewService;

    @BeforeEach
    void stubEmptyPage() {
        when(reviewService.getReviews(anyLong(), anyInt(), anyInt()))
                .thenReturn(PageResponse.from(new PageImpl<ReviewResponse>(List.of())));
    }

    @Test
    void defaultAndMaxSizeAreAccepted() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(reviewService).getReviews(1L, 0, 10);   // size 생략 = 기본 10
        mockMvc.perform(get(URL).param("page", "0").param("size", "10")).andExpect(status().isOk());
        mockMvc.perform(get(URL).param("page", "3").param("size", "20")).andExpect(status().isOk());
        verify(reviewService).getReviews(1L, 3, 20);
    }

    @Test
    void sizeAboveTwentyIsRejectedWithEnvelope() throws Exception {
        mockMvc.perform(get(URL).param("size", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000))
                .andExpect(jsonPath("$.result.size").exists());
        mockMvc.perform(get(URL).param("size", "100000")).andExpect(status().isBadRequest());
        verify(reviewService, never()).getReviews(anyLong(), anyInt(), anyInt());
    }

    @Test
    void zeroSizeIsRejectedWithEnvelopeInsteadOf500() throws Exception {
        mockMvc.perform(get(URL).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000))
                .andExpect(jsonPath("$.result.size").exists());
    }

    @Test
    void negativePageIsRejectedWithEnvelopeInsteadOf500() throws Exception {
        mockMvc.perform(get(URL).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000))
                .andExpect(jsonPath("$.result.page").exists());
        verify(reviewService, never()).getReviews(anyLong(), anyInt(), anyInt());
    }
}
