package com.ssafy.ddubi.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 발급·검증.
 *
 * Access 60분 / Refresh 14일(BACKEND_CONVENTION.md 3장 확정). Refresh 는 Redis 에 저장해 회전시키므로
 * deviceId 와 jti 를 함께 담는다 — 회전 시 저장된 jti 와 다르면 재사용으로 보고
 * 해당 기기 세션 전체를 폐기한다(7장 계약).
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** HS256 최소 키 길이. 이보다 짧으면 jjwt 가 서명 시점에 거부한다. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_DEVICE_ID = "did";
    private static final String CLAIM_ACCESS_VERSION = "aver";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = resolveSecretBytes(properties.secret());
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 설정 누락을 기동 시점에 터뜨린다.
     * 런타임에 발견하면 이미 로그인 API 가 500 을 뿜은 뒤다.
     */
    private static byte[] resolveSecretBytes(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "JWT 서명 키가 없습니다. 환경변수 JWT_SECRET 을 설정하세요 (ddubi.jwt.secret).");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 서명 키가 너무 짧습니다. HS256 은 최소 %d바이트가 필요한데 %d바이트입니다."
                            .formatted(MIN_SECRET_BYTES, bytes.length));
        }
        return bytes;
    }

    @PostConstruct
    void logConfiguration() {
        // 키 자체는 절대 찍지 않는다. 유효기간만 남겨 설정 실수를 눈으로 확인한다.
        log.info("JWT 설정 — access {}분 / refresh {}일",
                properties.accessTokenValidity().toMinutes(),
                properties.refreshTokenValidity().toDays());
    }

    public String createAccessToken(Long userId) {
        return createAccessToken(userId, 0);
    }

    public String createAccessToken(Long userId, long accessVersion) {
        return build(userId, TokenType.ACCESS, null, null, accessVersion,
                properties.accessTokenValidity());
    }

    /**
     * Refresh 토큰 발급. 반환된 jti 를 Redis 에 저장해 두었다가 회전 때 대조한다.
     */
    public IssuedRefreshToken createRefreshToken(Long userId, String deviceId) {
        String tokenId = UUID.randomUUID().toString();
        String token = build(userId, TokenType.REFRESH, deviceId, tokenId, 0,
                properties.refreshTokenValidity());
        return new IssuedRefreshToken(token, tokenId);
    }

    private String build(Long userId, TokenType type, String deviceId, String tokenId,
                         long accessVersion, Duration validity) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)));
        if (deviceId != null) {
            builder.claim(CLAIM_DEVICE_ID, deviceId);
        }
        if (tokenId != null) {
            builder.id(tokenId);
        }
        if (type == TokenType.ACCESS) {
            builder.claim(CLAIM_ACCESS_VERSION, accessVersion);
        }
        return builder.signWith(key).compact();
    }

    /**
     * 서명·만료를 검증하고 내용을 꺼낸다. 유효하지 않으면 비어 있는 Optional.
     *
     * 예외를 던지지 않는 이유: 인증 실패는 예외 상황이 아니라 정상 분기다.
     * 필터가 이 결과를 보고 SecurityContext 를 비워두면 EntryPoint 가 401 을 만든다.
     */
    public Optional<TokenPayload> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new TokenPayload(
                    Long.valueOf(claims.getSubject()),
                    TokenType.valueOf(claims.get(CLAIM_TYPE, String.class)),
                    claims.get(CLAIM_DEVICE_ID, String.class),
                    claims.getId(),
                    accessVersion(claims)));
        } catch (ExpiredJwtException e) {
            log.debug("만료된 토큰");
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            // 위조·형식 오류. 토큰 값 자체는 로그에 남기지 않는다.
            log.debug("유효하지 않은 토큰: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 용도까지 맞는지 확인한다. Refresh 토큰으로 API 를 호출하는 것을 막는 지점. */
    public Optional<TokenPayload> parse(String token, TokenType expected) {
        return parse(token).filter(payload -> payload.type() == expected);
    }

    private long accessVersion(Claims claims) {
        Object value = claims.get(CLAIM_ACCESS_VERSION);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public Duration accessTokenValidity() {
        return properties.accessTokenValidity();
    }

    public Duration refreshTokenValidity() {
        return properties.refreshTokenValidity();
    }

    /** 발급된 Refresh 토큰과 그 jti. jti 는 Redis 에 저장해 회전·재사용 감지에 쓴다. */
    public record IssuedRefreshToken(String token, String tokenId) {
    }
}
