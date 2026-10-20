package com.example.hangat.course.share;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.common.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
public class CourseShareController {
    private final CourseShareService shares;
    public CourseShareController(CourseShareService shares) { this.shares = shares; }
    @GetMapping("/courses/{courseId}/share")
    public BaseResponse<CourseShareService.State> status(@PathVariable Long courseId) {
        return BaseResponse.success(shares.status(courseId, CurrentUser.idOrNull()));
    }
    @PostMapping("/courses/{courseId}/share")
    public BaseResponse<CourseShareService.State> create(@PathVariable Long courseId) {
        return BaseResponse.success(shares.create(courseId, CurrentUser.idOrNull()));
    }
    @DeleteMapping("/courses/{courseId}/share")
    public BaseResponse<CourseShareService.State> revoke(@PathVariable Long courseId) {
        return BaseResponse.success(shares.revoke(courseId, CurrentUser.idOrNull()));
    }
    @GetMapping("/shared-courses/{token}")
    public BaseResponse<SharedCourseResponse> read(@PathVariable String token) {
        return BaseResponse.success(shares.read(token));
    }
}
