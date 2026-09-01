package com.ssafy.ddubi.global.exception;

/**
 * 도메인 규칙 위반. {@link GlobalExceptionHandler} 가 ErrorCode 의 HTTP 상태로 변환한다.
 *
 * Controller/Service 에서 try-catch 로 에러 응답을 직접 만들지 않는다 — 이 예외를 던진다.
 * 예상된 흐름이므로 스택트레이스는 채우지 않는다(로그 오염 방지).
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
