package com.ssafy.ddubi.global.config;

import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.InternalApiKeyFilter;
import com.ssafy.ddubi.global.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * 인증·인가 설정.
 *
 * 공개 경로 외에는 전부 인증이 필요하다. 토큰 해석은 {@link JwtAuthenticationFilter} 가 하고,
 * 이 체인은 "인증이 채워졌는가"만 본다.
 *
 * 인증 실패·인가 실패도 공통 오류 형식으로 내려야 하므로 EntryPoint/DeniedHandler 를 직접 지정한다
 * (여기서 처리하지 않으면 Security 가 @RestControllerAdvice 를 타지 않고 빈 401 을 반환한다).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 인증 없이 열어두는 경로.
     *
     * /auth/** 를 통째로 열지 않는다 — logout 은 명세서상 "로그인 사용자" 전용이라
     * 인증이 걸려야 한다. internal API 도 여기 넣지 않는다(서비스 계정 전용) —
     * 대신 ROLE_INTERNAL 을 요구하고, 그 역할은 InternalApiKeyFilter 만 부여한다.
     * permitAll 로 열지 않는 이유: 나중에 필터가 빠지거나 순서가 어긋났을 때
     * 조용히 열리는 것이 아니라 403 으로 닫혀야 한다.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/email/verification",
            "/api/v1/auth/email/verification/confirm",
            "/api/v1/auth/local/signup",
            "/api/v1/auth/local/login",
            "/api/v1/auth/oauth",
            "/api/v1/auth/oauth/token",
            "/api/v1/auth/oauth/id-token",
            // 연동 확정은 로그인 전 단계다. 소셜 로그인이 ACCOUNT_LINK_REQUIRED 를 준
            // 시점에는 아직 토큰이 없다. 인증은 linkToken + 기존 계정 비밀번호로 한다.
            "/api/v1/auth/oauth/link",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          InternalApiKeyFilter internalApiKeyFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.internalApiKeyFilter = internalApiKeyFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 파이프라인 전용. 역할은 InternalApiKeyFilter 만 부여한다.
                        .requestMatchers("/internal/**").hasRole("INTERNAL")
                        .anyRequest().authenticated())
                // 표준 폼 로그인 필터 자리에 JWT 해석을 끼운다.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // /internal 은 JWT 가 아니라 API 키로 인증한다. JWT 필터보다 앞에 둔다.
                .addFilterBefore(internalApiKeyFilter, JwtAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                write(res, objectMapper, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) ->
                                write(res, objectMapper, ErrorCode.FORBIDDEN_RESOURCE)))
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 운영에서는 Nginx 가 같은 오리진으로 서빙하므로 CORS 가 필요 없지만,
     * Flutter Web 로컬 개발(flutter run -d chrome)이 다른 포트를 쓴다.
     * 허용 오리진은 환경변수로 주입할 것 — 여기 하드코딩하지 말 것.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(),
                ApiResponse.error(code, code.defaultMessage()));
    }
}
