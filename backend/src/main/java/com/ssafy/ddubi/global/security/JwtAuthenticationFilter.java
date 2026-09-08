package com.ssafy.ddubi.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer {accessToken} 을 읽어 SecurityContext 에 인증을 채운다.
 *
 * 토큰이 없거나 유효하지 않으면 아무것도 하지 않고 통과시킨다 — 여기서 401 을 만들지 않는다.
 * 보호된 경로면 뒤쪽 인가 단계에서 걸리고 SecurityConfig 의 EntryPoint 가 공통 오류 형식으로 응답한다.
 * 공개 경로(/auth/**, swagger 등)는 토큰이 없어도 정상 통과해야 하므로 이 구조여야 한다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final RevokedAccessTokenStore revokedStore;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   RevokedAccessTokenStore revokedStore) {
        this.tokenProvider = tokenProvider;
        this.revokedStore = revokedStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        resolveToken(request)
                // ACCESS 만 통과시킨다. Refresh 토큰으로는 API 를 호출할 수 없다.
                .flatMap(token -> tokenProvider.parse(token, TokenType.ACCESS))
                // 서명이 유효해도 탈퇴·탈취로 무효화된 사용자면 인증하지 않는다.
                // JWT 는 발급 후 취소가 안 되므로 이 확인이 유일한 즉시 차단 수단이다.
                .filter(payload -> !revokedStore.isRevoked(
                        payload.userId(), payload.accessVersion()))
                .ifPresent(payload -> authenticate(payload, request));
        chain.doFilter(request, response);
    }

    private java.util.Optional<String> resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return java.util.Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(token);
    }

    private void authenticate(TokenPayload payload, HttpServletRequest request) {
        // principal 은 userId(Long). @LoginUserId 가 이 값을 그대로 꺼내 쓴다.
        var authentication = new UsernamePasswordAuthenticationToken(
                payload.userId(), null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
