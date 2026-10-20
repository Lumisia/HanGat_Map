package com.example.hangat.course;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.CourseResponseDto;
import com.example.hangat.course.model.CourseClaimRequest;
import com.example.hangat.course.model.CourseClaimResponse;
import com.example.hangat.course.model.CourseClaimRenewal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final CourseClaimService courseClaimService;
    private final CourseClaimTokenService courseClaimTokenService;

    @PostMapping("/courses/{courseId}/claim/renew")
    public BaseResponse<CourseClaimRenewal.Response> renewClaim(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseClaimRenewal.Request request,
            Authentication authentication,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        Long userId = isAuthenticatedUser(authentication) ? (Long) authentication.getPrincipal() : null;
        var proof = userId == null
                ? courseClaimService.renew(courseId, request.claimToken())
                : courseClaimService.renew(courseId, request.claimToken(), userId);
        return BaseResponse.success(new CourseClaimRenewal.Response(proof.token(), proof.expiresAt()));
    }

    @PostMapping("/courses")
    public BaseResponse<CourseResponseDto> createCourse(
            @Valid @RequestBody CourseRequestDto request,
            Authentication authentication
    ) {
        CourseResponseDto response = courseService.createCourse(request);
        if (isAuthenticatedUser(authentication)) {
            return BaseResponse.success(response);
        }

        CourseClaimTokenService.ClaimProof proof =
                courseClaimTokenService.issue(response.id());
        return BaseResponse.success(response.withClaimProof(proof.token(), proof.expiresAt()));
    }

    @PostMapping("/courses/{courseId}/claim")
    public BaseResponse<CourseClaimResponse> claimCourse(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseClaimRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return BaseResponse.success(courseClaimService.claim(courseId, userId, request));
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Long;
    }
}
