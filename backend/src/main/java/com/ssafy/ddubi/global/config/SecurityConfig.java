package com.ssafy.ddubi.global.config;

import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Phase 0 보안 골격.
 *
 * JWT 인증 필터는 Phase 1(인증·회원)에서 이 체인에 끼운다. 지금은
 * "공개 경로만 열고 나머지는 401" 상태이며, 도메인 엔드포인트가 아직 없어 문제되지 않는다.
 * 인증 실패·인가 실패도 공통 오류 형식으로 내려야 하므로 EntryPoint/DeniedHandler 를 직접 지정한다
 * (여기서 처리하지 않으면 Security 가 @RestControllerAdvice 를 타지 않고 빈 401 을 반환한다).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 인증 없이 열어두는 경로. internal API 는 여기 넣지 않는다(서비스 계정 전용). */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
    };

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
                        .anyRequest().authenticated())
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
