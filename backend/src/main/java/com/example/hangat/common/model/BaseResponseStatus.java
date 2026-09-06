package com.example.hangat.common.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공통 응답 코드 (Nexus 컨벤션)
 * - 2000번대: 성공
 * - 3000번대: 클라이언트 오류 (3000 공통 / 3100 회원 / 3200 장소 / 3300 코스 / 3400 혼잡)
 * - 5000번대: 서버 오류
 */
@AllArgsConstructor
@Getter
public enum BaseResponseStatus {
    // 2000번대 성공
    SUCCESS(true, 2000, "요청 성공"),

    // 3000번대 공통 클라이언트 오류
    REQUEST_ERROR(false, 3000, "입력값을 확인해주세요."),
    JWT_EXPIRED(false, 3001, "JWT 토큰 만료"),
    JWT_INVALID(false, 3002, "JWT 토큰 유효하지 않음"),
    TOO_MANY_REQUESTS(false, 3003, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // 3100번대 회원
    USER_NOT_FOUND(false, 3101, "존재하지 않는 사용자입니다."),
    DUPLICATE_EMAIL(false, 3102, "이미 사용중인 이메일입니다."),
    DUPLICATE_NICKNAME(false, 3104, "이미 사용중인 닉네임입니다."),
    EMAIL_NOT_VERIFIED(false, 3105, "이메일 인증을 완료해주세요."),
    ACCOUNT_SUSPENDED(false, 3106, "이용이 제한된 계정입니다. 고객센터로 문의해주세요."),
    ACCOUNT_WITHDRAWN(false, 3107, "탈퇴한 계정입니다. 새로 가입해주세요."),
    PASSWORD_WRONG(false, 3108, "이메일 또는 비밀번호가 올바르지 않습니다."),
    PASSWORD_TOO_SHORT(false, 3109, "비밀번호는 12자 이상이어야 합니다."),
    PASSWORD_TOO_LONG(false, 3110, "비밀번호가 너무 깁니다. 한글은 24자까지 가능합니다."),
    PASSWORD_CONFIRM_MISMATCH(false, 3111, "비밀번호가 일치하지 않습니다."),

    // 3150번대 OAuth·인증 보안
    OAUTH_LOGIN_FAILED(false, 3150, "소셜 로그인에 실패했습니다."),
    OAUTH_FLOW_INVALID(false, 3151, "소셜 로그인 요청이 만료됐습니다. 다시 로그인해주세요."),
    OAUTH_CODE_INVALID(false, 3152, "인증 코드를 확인해주세요."),
    SOCIAL_PROVIDER_ALREADY_LINKED(false, 3153, "해당 소셜 로그인 제공자가 이미 연결되어 있습니다."),

    // 3200번대 장소
    PLACE_NOT_FOUND(false, 3201, "존재하지 않는 장소입니다."),
    REVIEW_NOT_FOUND(false, 3211, "존재하지 않는 후기입니다."),
    REVIEW_FORBIDDEN(false, 3212, "본인이 작성한 후기만 삭제할 수 있습니다."),
    REVIEW_RATING_OR_REPORT_REQUIRED(false, 3213, "별점 또는 혼잡 제보 중 하나는 필요합니다."),
    REVIEW_TOO_MANY_IMAGES(false, 3214, "후기 사진은 최대 5장까지입니다."),
    LOGIN_REQUIRED(false, 3215, "로그인이 필요합니다."),

    // 3300번대 코스
    COURSE_NOT_FOUND(false, 3301, "존재하지 않는 코스입니다."),
    COURSE_INVALID_CONDITION(false, 3302, "코스 생성 조건이 유효하지 않습니다."),
    COURSE_CLAIM_INVALID(false, 3303, "코스 저장 증명을 확인해주세요."),
    COURSE_CLAIM_EXPIRED(false, 3304, "코스 저장 가능 시간이 만료되었습니다."),
    COURSE_NOT_CLAIMABLE(false, 3305, "저장할 수 없는 코스입니다."),
    COURSE_ITEM_NOT_FOUND(false, 3306, "존재하지 않는 코스 일정입니다."),
    COURSE_FORBIDDEN(false, 3307, "본인의 코스만 변경할 수 있습니다."),
    COURSE_PLACE_DUPLICATED(false, 3308, "이미 코스에 담긴 장소입니다."),

    // 3400번대 혼잡 예보
    CONGESTION_NOT_FOUND(false, 3401, "해당 날짜의 혼잡 예보가 없습니다."),

    // 5000번대 서버 오류
    FAIL(false, 5000, "요청 실패"),
    DATABASE_ERROR(false, 5001, "데이터베이스 연결 및 처리 오류"),
    EXTERNAL_API_ERROR(false, 5002, "외부 API 호출에 실패했습니다."),
    AI_COURSE_TEMPORARILY_UNAVAILABLE(
            false,
            5003,
            "AI 코스 생성 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해 주세요.");

    private final boolean success;
    private final int code;
    private final String message;
}
