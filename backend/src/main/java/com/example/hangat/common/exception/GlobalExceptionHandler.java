package com.example.hangat.common.exception;

import com.example.hangat.common.model.BaseResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.ai.CourseAiException;
import com.example.hangat.course.ai.CourseAiFailureType;
import com.example.hangat.course.route.CourseCarRouteException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** 공통 예외 → BaseResponse 변환 (Nexus 컨벤션) */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** @Valid 검증 실패 → 필드별 메시지를 result에 담아 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.fail(BaseResponseStatus.REQUEST_ERROR, errors));
    }

    /** @RequestParam·@PathVariable 검증 실패 → 필드별 메시지를 result에 담아 400 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<Map<String, String>>> handleConstraintViolation(ConstraintViolationException e) {
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            errors.put(field, violation.getMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.fail(BaseResponseStatus.REQUEST_ERROR, errors));
    }

    /** JSON 문법 오류나 enum 변환 실패 등 요청 본문 역직렬화 실패 → 공통 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Object>> handleUnreadableRequest(
            HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.fail(BaseResponseStatus.REQUEST_ERROR));
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<BaseResponse<Object>> handleBaseException(BaseException e) {
        BaseResponseStatus status = e.getStatus();
        BaseResponse<Object> response = e.getResult() != null
                ? BaseResponse.fail(status, e.getResult())
                : BaseResponse.fail(status);
        return ResponseEntity.status(httpStatusOf(status.getCode())).body(response);
    }

    @ExceptionHandler(CourseAiException.class)
    public ResponseEntity<BaseResponse<Object>> handleCourseAiException(CourseAiException e) {
        if (e.getFailureType() == CourseAiFailureType.RATE_LIMIT
                || e.getFailureType() == CourseAiFailureType.TEMPORARILY_UNAVAILABLE) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(BaseResponse.fail(
                            BaseResponseStatus.AI_COURSE_TEMPORARILY_UNAVAILABLE));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.fail(BaseResponseStatus.EXTERNAL_API_ERROR));
    }

    @ExceptionHandler(CourseCarRouteException.class)
    public ResponseEntity<BaseResponse<Object>> handleCourseCarRouteException(CourseCarRouteException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(BaseResponse.fail(BaseResponseStatus.EXTERNAL_API_ERROR));
    }

    /**
     * DB 제약 위반 (UNIQUE, NOT NULL 등).
     * 중복 검사와 저장 사이에 다른 요청이 끼면 여기로 온다 - 가입 버튼 더블클릭이 대표적임.
     * 어느 제약이 깨졌는지는 알 수 없어서 메시지는 일반적으로 나간다.
     * 정상 경로에서는 서비스의 사전 검사가 정확한 메시지를 준다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<BaseResponse<Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.fail(BaseResponseStatus.REQUEST_ERROR));
    }

    /** 3000번대 → 400, 5000번대 → 500 */
    private int httpStatusOf(int errorCode) {
        return errorCode >= 5000 ? 500 : 400;
    }
}
