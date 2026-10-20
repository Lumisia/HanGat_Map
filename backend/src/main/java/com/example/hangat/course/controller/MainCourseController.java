package com.example.hangat.course.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.course.model.CourseSummaryResponse;
import com.example.hangat.course.service.MainCourseService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 메인 추천 코스 카드 - /main/** 이라 비로그인 열람 가능(SecurityConfig permitAll).
 * 저장·공유는 별도 /courses API에서 인증 필수로 열린다.
 */
@RestController
@RequestMapping("/main")
public class MainCourseController {

    private final MainCourseService mainCourseService;

    public MainCourseController(MainCourseService mainCourseService) {
        this.mainCourseService = mainCourseService;
    }

    @GetMapping("/courses")
    @Operation(summary = "오늘의 추천 코스 카드", description = """
            새벽 배치가 사전 생성한 샘플 코스 - 그날 혼잡 예보가 한산한 권역에서 서로 다른 권역 하나씩,
            기간은 프리셋별 1박2일 또는 2박3일. 비 예보 날은 실내 후보를 하루 정원까지 우선 배치한다.
            일부 프리셋의 배치가 실패하면 그 자리는 직전 성공분(출발일이 지나지 않은 것만)이 지킨다.
            생성분이 없으면 빈 목록 - 프론트는 빈 배열도 목업 폴백 조건에 포함해야 한다.
            혼잡 예보는 날짜 단위로만 제공된다 (시간대 예측 없음).""")
    public BaseResponse<List<CourseSummaryResponse>> mainCourses() {
        return BaseResponse.success(mainCourseService.mainCourses());
    }
}
