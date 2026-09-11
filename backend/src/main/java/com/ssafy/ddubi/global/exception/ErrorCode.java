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

    /**
     * 계정 연동 티켓이 만료됐거나 이미 썼다.
     *
     * <p>비밀번호 불일치와 코드를 나누는 이유는 <b>원인을 알려 주기 위해서</b>지, 복구 방법이
     * 달라서가 아니다. 티켓은 실패해도 소비되므로 <b>어느 쪽이든 소셜 로그인부터 다시</b> 해야 한다.
     */
    LINK_TOKEN_INVALID(HttpStatus.UNAUTHORIZED,
            "연동 요청이 만료되었거나 이미 사용되었습니다. 소셜 로그인부터 다시 시도해 주세요."),

    /**
     * 연동 중 비밀번호가 틀렸다. <b>티켓은 이미 소비됐다.</b>
     *
     * 일반 로그인 실패({@link #UNAUTHORIZED})와 구분하는 이유: 화면이 "비밀번호 다시 입력" 이
     * 아니라 <b>"소셜 로그인부터 다시"</b> 로 가야 한다. 같은 코드로 주면 프론트가 입력창만
     * 비우고 재시도를 유도하는데, 그 티켓으로는 무엇을 넣어도 통하지 않는다.
     */
    LINK_PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED,
            "비밀번호가 일치하지 않습니다. 보안을 위해 연동 요청이 취소되었으니 "
                    + "소셜 로그인부터 다시 시도해 주세요."),

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

    /**
     * 그 소셜 계정이 <b>다른 사용자</b>에게 이미 연결돼 있다.
     *
     * 연동 화면을 띄운 뒤 다른 창에서 같은 소셜을 다른 계정에 붙인 경우다. 조용히 옮기면
     * 먼저 붙여 둔 쪽이 로그인 수단을 잃는다.
     */
    SOCIAL_ALREADY_LINKED(HttpStatus.CONFLICT, "이 소셜 계정은 다른 사용자에게 연결되어 있습니다."),

    /**
     * 같은 이메일의 기존 계정에 <b>비밀번호가 없다</b> — 소셜로만 가입한 계정이다.
     *
     * 확인할 비밀번호가 없어 소유를 증명받을 방법이 없다. 그렇다고 새 계정을 만들 수도 없다:
     * {@code UK_USER_EMAIL} 때문에 같은 이메일로 두 번째 사용자를 만들 수 없고, 만들어진다
     * 해도 사용자는 자기 일정이 사라진 줄 안다. 그래서 <b>기존에 쓰던 소셜로 로그인하라고
     * 안내</b>한다. 마이페이지에서 새 소셜을 추가로 연결하면 된다.
     */
    ACCOUNT_LINK_UNAVAILABLE(HttpStatus.CONFLICT, "이 이메일은 다른 로그인 수단으로 가입되어 있습니다."),
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
    REALTIME_DATA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실시간 교통 데이터를 사용할 수 없습니다."),

    /**
     * 필요한 설정이 없어 그 기능만 닫아 둔 상태.
     *
     * INTERNAL_ERROR 와 구분하는 이유: 서버가 고장 난 것이 아니라 <b>운영자가 값을 넣으면
     * 해결되는</b> 상태다. 모니터링에서 장애로 집계되면 안 되고, 받는 쪽도 재시도가
     * 의미 없다는 것을 알아야 한다.
     */
    SERVICE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "필요한 설정이 없어 사용할 수 없습니다.");

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
