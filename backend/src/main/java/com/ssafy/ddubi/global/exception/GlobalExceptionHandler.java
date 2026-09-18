package com.ssafy.ddubi.global.exception;

import com.ssafy.ddubi.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 전역 예외 → 공통 오류 응답 변환.
 * 내부 예외 메시지·스택·SQL 은 절대 클라이언트로 내보내지 않는다
 * (외부 API 키, raw_response 노출 방지 — BACKEND_CONVENTION.md 9장).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 도메인 규칙 위반 — 예상된 흐름이라 WARN 으로만 남긴다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        log.warn("business error: {} - {}", code, e.getMessage());
        return respond(code, e.getMessage(), List.of());
    }

    /** @Valid 실패 — 어떤 필드가 왜 틀렸는지 fieldErrors 로 내려준다. */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException e) {
        List<ApiResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), fieldErrors);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        log.warn("bad request: {}", e.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e) {
        return respond(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage(), List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return respond(ErrorCode.FORBIDDEN_RESOURCE, ErrorCode.FORBIDDEN_RESOURCE.defaultMessage(), List.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoEndpoint(Exception e) {
        return respond(ErrorCode.INVALID_REQUEST, "요청한 엔드포인트를 찾을 수 없습니다.", List.of());
    }

    /**
     * UNIQUE·FK 제약 위반 — 500 이 아니라 409 로 준다.
     *
     * 대부분 같은 리소스를 동시에 만들려다 한쪽이 진 경우다(예: 같은 주의 리포트).
     * 제약이 제 일을 해서 중복이 안 생긴 것이므로 서버 오류가 아니고,
     * <b>다시 보내면 상대가 만든 행을 읽어 성공한다.</b> 그 사실을 상태 코드로 알려야
     * 클라이언트가 재시도할 수 있다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e,
                                                                 HttpServletRequest request) {
        log.warn("제약 위반 at {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.DUPLICATE_RESOURCE,
                "동시에 같은 요청이 처리됐습니다. 다시 시도해 주세요.", List.of());
    }

    /** 마지막 방어선 — 원인은 서버 로그에만 남기고 응답에는 일반 문구만 준다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled exception at {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
    }

    private ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message,
                                                      List<ApiResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, message, fieldErrors));
    }
}
