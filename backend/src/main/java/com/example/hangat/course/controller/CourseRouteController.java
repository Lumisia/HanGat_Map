package com.example.hangat.course.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.common.security.CurrentUser;
import com.example.hangat.course.route.CarRouteResponse;
import com.example.hangat.course.route.CourseCarRouteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseRouteController {
    private final CourseCarRouteService service;
    public CourseRouteController(CourseCarRouteService service) { this.service = service; }

    @GetMapping("/courses/{courseId}/routes/car")
    public BaseResponse<CarRouteResponse> carRoute(@PathVariable Long courseId) {
        return BaseResponse.success(service.route(courseId, CurrentUser.idOrNull()));
    }
}
