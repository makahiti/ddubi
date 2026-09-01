package com.ssafy.ddubi.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.RequestIdHolder;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 전 API 공통 응답 래퍼 (뚜비 API 명세서 v2.1.1 §2).
 *
 * <pre>
 * 성공 {"success":true, "data":{...},  "timestamp":"2026-08-31T19:30:00+09:00","requestId":"..."}
 * 실패 {"success":false,"error":{"code","message","fieldErrors":[]}, "timestamp":"...","requestId":"..."}
 * </pre>
 *
 * Controller 는 raw DTO 를 그대로 반환하지 않고 반드시 이 래퍼로 감싼다.
 * timestamp 는 ISO 8601 KST, requestId 는 {@link RequestIdHolder} 가 채운다.
 * JSON 필드는 camelCase 다 — 명세서 v2.1.1 기준이므로 snake_case 로 바꾸지 말 것.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorBody error,
        OffsetDateTime timestamp,
        String requestId
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, now(), RequestIdHolder.get());
    }

    /**
     * 본문 데이터가 없는 성공 응답 (예: PATCH .../read, 204 대체).
     *
     * 이름이 {@code success()} 가 아닌 이유: 레코드 컴포넌트 {@code success} 의
     * 접근자와 시그니처가 겹쳐 컴파일되지 않는다.
     */
    public static ApiResponse<Void> empty() {
        return new ApiResponse<>(true, null, null, now(), RequestIdHolder.get());
    }

    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return error(code, message, List.of());
    }

    public static ApiResponse<Void> error(ErrorCode code, String message, List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, null,
                new ErrorBody(code.name(), message, fieldErrors),
                now(), RequestIdHolder.get());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(KST);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String reason) {
    }
}
