package com.ssafy.ddubi.global.filter;

import com.ssafy.ddubi.global.util.RequestIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * X-Request-Id 를 받아 MDC 에 넣고, 없으면 서버가 발급한다(명세서 v2.1.1 §2 필수).
 * 발급한 값은 응답 헤더로도 돌려줘서 클라이언트 로그와 대조할 수 있게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(RequestIdHolder.HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        RequestIdHolder.set(requestId);
        response.setHeader(RequestIdHolder.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 스레드 재사용 시 이전 요청의 ID 가 남지 않도록 반드시 정리한다
            RequestIdHolder.clear();
        }
    }
}
