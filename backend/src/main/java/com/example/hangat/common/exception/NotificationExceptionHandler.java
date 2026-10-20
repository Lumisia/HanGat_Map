package com.example.hangat.common.exception;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.course.controller.AsyncCourseController;
import com.example.hangat.notification.controller.NotificationController;
import com.example.hangat.notification.controller.TripNotificationController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 비동기 작업 / 알림 / 여행 확정 API의 HTTP 오류 처리.
 * 기존 전역 예외 처리보다 먼저 필요한 예외만 처리한다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        AsyncCourseController.class,
        NotificationController.class,
        TripNotificationController.class
})
public class NotificationExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<Void>> handle(
            ResponseStatusException exception
    ) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new BaseResponse<>(
                        false,
                        exception.getStatusCode().value(),
                        exception.getReason() == null
                                ? "요청을 처리하지 못했습니다."
                                : exception.getReason(),
                        null
                ));
    }

    /**
     * 누락된 version이나 숫자가 아닌 파라미터는 500이 아닌 400.
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<BaseResponse<Void>> invalidParameter(
            Exception exception
    ) {
        return ResponseEntity.badRequest()
                .body(new BaseResponse<>(
                        false,
                        400,
                        "요청 파라미터를 확인하세요.",
                        null
                ));
    }
}
