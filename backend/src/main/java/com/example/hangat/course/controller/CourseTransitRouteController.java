package com.example.hangat.course.controller;
import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.common.security.CurrentUser;
import com.example.hangat.course.transit.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class CourseTransitRouteController {
    private final CourseTransitRouteService service;
    public CourseTransitRouteController(CourseTransitRouteService service){this.service=service;}
    @GetMapping("/courses/{courseId}/routes/transit")
    public ResponseEntity<BaseResponse<TransitRouteResponse>> route(@PathVariable Long courseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.success(service.route(courseId,CurrentUser.idOrNull())));
    }
}
