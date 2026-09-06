package com.example.hangat.course.controller;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.security.CurrentUser;
import com.example.hangat.course.CourseAccommodationService;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseAccommodationUpdateRequest;
import com.example.hangat.course.model.CourseAccommodationSearchRequest;
import com.example.hangat.course.model.CourseRenameRequest;
import com.example.hangat.course.model.CourseSummaryResponse;
import com.example.hangat.course.service.CourseCommandService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 저장 코스 관리 (MY_001) - 이름 변경·삭제. 둘 다 로그인 + 본인 코스만. */
@RestController
public class CourseCommandController {

    private final CourseCommandService courseCommandService;
    private final CourseAccommodationService courseAccommodationService;

    public CourseCommandController(
            CourseCommandService courseCommandService,
            CourseAccommodationService courseAccommodationService
    ) {
        this.courseCommandService = courseCommandService;
        this.courseAccommodationService = courseAccommodationService;
    }

    @PatchMapping("/courses/{courseId}")
    @Operation(summary = "저장 코스 이름 변경",
            description = "본인이 저장한 코스의 이름만 바꾼다. 일정은 스왑 API로 바꾼다.")
    public BaseResponse<CourseSummaryResponse> rename(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseRenameRequest request) {
        return BaseResponse.success(
                courseCommandService.rename(courseId, request.title(), requireLogin()));
    }

    @PatchMapping("/courses/{courseId}/accommodation")
    @Operation(summary = "AI 코스 추천 숙소 저장",
            description = "READY 코스는 발급된 claim proof, SAVED 코스는 소유자 인증으로 숙소를 변경한다.")
    public BaseResponse<AccommodationDto> updateAccommodation(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseAccommodationUpdateRequest request
    ) {
        return BaseResponse.success(courseAccommodationService.update(
                courseId, request, CurrentUser.idOrNull()));
    }

    @PostMapping("/courses/{courseId}/accommodations/search")
    @Operation(summary = "AI 코스 주변 실제 숙소 추천",
            description = "저장된 일정 좌표를 기준으로 Kakao AD5 숙박 장소만 조회한다.")
    public BaseResponse<List<AccommodationDto>> recommendAccommodations(
            @PathVariable Long courseId,
            @RequestBody CourseAccommodationSearchRequest request
    ) {
        return BaseResponse.success(courseAccommodationService.recommend(
                courseId, request, CurrentUser.idOrNull()));
    }

    @DeleteMapping("/courses/{courseId}")
    @Operation(summary = "저장 코스 삭제", description = """
            논리 삭제라 되돌릴 여지를 남긴다. 이미 지운 코스를 또 지워도 성공으로 답한다(멱등).""")
    public BaseResponse<Void> delete(@PathVariable Long courseId) {
        courseCommandService.delete(courseId, requireLogin());
        return BaseResponse.success(null);
    }

    private Long requireLogin() {
        Long userId = CurrentUser.idOrNull();
        if (userId == null) {
            throw new BaseException(BaseResponseStatus.LOGIN_REQUIRED);
        }
        return userId;
    }
}
