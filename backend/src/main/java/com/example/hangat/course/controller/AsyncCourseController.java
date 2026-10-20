package com.example.hangat.course.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.course.AsyncCourseService;
import com.example.hangat.course.model.CourseRequestDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 회원 AI 코스 생성 작업 API.
 * 접수는 202로 응답하고 진행 상태와 결과는 별도로 조회한다.
 */
@RestController
@Profile("!batch")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "hangat.async.enabled",
        havingValue = "true"
)
public class AsyncCourseController {

    private final AsyncCourseService jobs;

    public record SubmitRequest(
            @NotNull UUID requestKey,
            @NotNull @Valid CourseRequestDto request
    ) {
    }

    @PostMapping("/course-generation-jobs")
    public ResponseEntity<BaseResponse<Map<String, Object>>> submit(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody SubmitRequest request
    ) {
        var result = jobs.submit(
                userId,
                request.requestKey().toString(),
                request.request()
        );

        return ResponseEntity.accepted()
                .header("Cache-Control", "no-store")
                .body(BaseResponse.success(result));
    }

    @GetMapping("/course-generation-jobs/{id}")
    public ResponseEntity<BaseResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal Long userId,
            @PathVariable UUID id
    ) {
        return response(jobs.status(id.toString(), userId));
    }

    @GetMapping("/course-generation-jobs/{id}/result")
    public ResponseEntity<BaseResponse<Map<String, Object>>> result(
            @AuthenticationPrincipal Long userId,
            @PathVariable UUID id
    ) {
        return response(jobs.result(id.toString(), userId));
    }

    @GetMapping("/users/me/course-generation-jobs")
    public ResponseEntity<BaseResponse<Map<String, Object>>> recent(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return response(jobs.recent(userId, page, size));
    }

    private ResponseEntity<BaseResponse<Map<String, Object>>> response(
            Map<String, Object> value
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(BaseResponse.success(value));
    }
}
