package com.ssafy.ddubi.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 전역 에러 코드 (뚜비 API 명세서 v2.1.1 §2).
 *
 * 응답의 {@code error.code} 는 이 enum 이름을 그대로 쓴다 — 프론트가 이 문자열로 분기하므로
 * 이름을 바꾸거나 지우지 말 것. 새 에러가 필요하면 여기에 추가하고,
 * Controller/Service 에서 문자열 코드를 직접 만들지 않는다.
 */
public enum ErrorCode {

    /* 400 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),

    /* 401 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

    /* 403 */
    FORBIDDEN_RESOURCE(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    /* 404 */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."),
    SCHEDULE_REPEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "반복 일정을 찾을 수 없습니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다."),
    RECOMMENDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "추천 결과를 찾을 수 없습니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    DEVICE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 기기 토큰입니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."),

    /* 409 */
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
    STALE_RECOMMENDATION(HttpStatus.CONFLICT, "추천 결과가 최신이 아닙니다. 다시 조회해 주세요."),

    /* 422 */
    ROUTE_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "이용 가능한 경로를 찾지 못했습니다."),
    OUT_OF_SERVICE_AREA(HttpStatus.UNPROCESSABLE_ENTITY, "서비스 지역이 아닙니다."),

    /* 429 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    /* 500 / 503 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    // 카카오 길찾기 연결 실패·응답 손상처럼 우리 서버 밖의 길찾기 제공자 문제
    ROUTE_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 길찾기 서비스를 사용할 수 없습니다."),
    REALTIME_DATA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실시간 교통 데이터를 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
