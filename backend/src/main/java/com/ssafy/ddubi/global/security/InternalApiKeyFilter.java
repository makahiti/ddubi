package com.ssafy.ddubi.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * {@code /internal/**} 전용 API 키 검증. 계약: {@code docs/contracts/internal-api.yaml}
 *
 * <h3>키가 없으면 열지 않고 닫는다</h3>
 * 메일·FCM 은 자격증명이 없으면 로그 발송기로 <b>대체</b>하지만 여기는 반대로 <b>거절</b>한다.
 * 그쪽은 못 보내는 것이 전부지만, 이쪽은 열어두면 서버1 IP 를 위조하거나 네트워크 안에
 * 들어온 누구나 <b>기준선을 통째로 갈아치울 수 있다.</b> 추천의 근거 데이터가 오염된다.
 *
 * 그래서 키가 비어 있으면 {@code /internal/**} 만 503 으로 닫는다.
 * 기동 자체를 막지는 않는다 — 사용자 API 는 멀쩡히 떠야 한다.
 *
 * <h3>비교를 상수 시간으로 하는 이유</h3>
 * {@code equals} 는 다른 문자가 나오는 즉시 멈춰서, 응답 시간 차이로 키를 앞에서부터
 * 한 글자씩 알아낼 수 있다. 사설망 안이라 현실적 위험은 낮지만 비용이 없는 방어다.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);

    public static final String HEADER = "X-DDUBI-API-KEY";
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String INTERNAL_PRINCIPAL = "internal-pipeline";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(@Value("${ddubi.internal.api-key:}") String apiKey,
                                ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!StringUtils.hasText(apiKey)) {
            log.error("INTERNAL_API_KEY 미설정 — /internal 요청을 거절합니다. uri={}", request.getRequestURI());
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.SERVICE_NOT_CONFIGURED,
                    "내부 API 키가 설정되지 않았습니다.");
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || !matches(provided)) {
            // 키 값은 물론이고 받은 값도 로그에 남기지 않는다.
            log.warn("내부 API 키 불일치 — uri={} remote={}", request.getRequestURI(), request.getRemoteAddr());
            write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                    "내부 API 키가 올바르지 않습니다.");
            return;
        }

        // 인증 주체를 세운다. SecurityConfig 가 /internal/** 에 hasRole("INTERNAL") 을 요구하므로,
        // 이 필터가 빠지거나 순서가 어긋나면 열리는 것이 아니라 403 으로 닫힌다.
        var auth = new UsernamePasswordAuthenticationToken(
                INTERNAL_PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean matches(String provided) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8));
    }

    private void write(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
