package com.example.hangat.course.route;

/** Provider business outcome, never a transient network retry. */
final class RouteCoordinateException extends CourseCarRouteException {
    private final int resultCode;
    RouteCoordinateException(int resultCode) {
        super("Kakao Mobility cannot resolve a route coordinate.");
        this.resultCode = resultCode;
    }
    int resultCode() { return resultCode; }
}
