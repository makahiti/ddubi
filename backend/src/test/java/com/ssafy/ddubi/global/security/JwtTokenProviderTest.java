package com.ssafy.ddubi.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 발급·검증 단위 테스트. Spring 컨텍스트 없이 돈다.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "ddubi-test-secret-key-must-be-at-least-32-bytes";

    private JwtTokenProvider provider(Duration accessValidity) {
        return new JwtTokenProvider(new JwtProperties(SECRET, accessValidity, Duration.ofDays(14)));
    }

    private JwtTokenProvider provider() {
        return provider(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("Access 토큰을 발급하면 userId 와 ACCESS 타입을 되찾을 수 있다")
    void createAndParseAccessToken() {
        JwtTokenProvider provider = provider();

        String token = provider.createAccessToken(42L, 7L);

        assertThat(provider.parse(token)).hasValueSatisfying(payload -> {
            assertThat(payload.userId()).isEqualTo(42L);
            assertThat(payload.type()).isEqualTo(TokenType.ACCESS);
            assertThat(payload.accessVersion()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("Refresh 토큰은 deviceId 와 jti 를 담는다 — Redis 회전·재사용 감지의 근거")
    void refreshTokenCarriesDeviceIdAndTokenId() {
        JwtTokenProvider provider = provider();

        var issued = provider.createRefreshToken(42L, "device-A");

        assertThat(issued.tokenId()).isNotBlank();
        assertThat(provider.parse(issued.token())).hasValueSatisfying(payload -> {
            assertThat(payload.userId()).isEqualTo(42L);
            assertThat(payload.type()).isEqualTo(TokenType.REFRESH);
            assertThat(payload.deviceId()).isEqualTo("device-A");
            assertThat(payload.tokenId()).isEqualTo(issued.tokenId());
        });
    }

    @Test
    @DisplayName("Refresh 토큰은 Access 로 통하지 않는다 — 탈취 시 피해 범위를 60분으로 묶는다")
    void refreshTokenIsRejectedWhereAccessIsExpected() {
        JwtTokenProvider provider = provider();
        var refresh = provider.createRefreshToken(42L, "device-A");

        assertThat(provider.parse(refresh.token(), TokenType.ACCESS)).isEmpty();
        assertThat(provider.parse(refresh.token(), TokenType.REFRESH)).isPresent();
    }

    @Test
    @DisplayName("발급할 때마다 jti 가 달라야 회전 후 이전 토큰을 구분할 수 있다")
    void eachRefreshTokenHasDistinctTokenId() {
        JwtTokenProvider provider = provider();

        var first = provider.createRefreshToken(42L, "device-A");
        var second = provider.createRefreshToken(42L, "device-A");

        assertThat(first.tokenId()).isNotEqualTo(second.tokenId());
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 거부한다")
    void rejectsTokenSignedWithAnotherKey() {
        JwtTokenProvider attacker = new JwtTokenProvider(
                new JwtProperties("another-secret-key-that-is-long-enough-32", Duration.ofMinutes(60), Duration.ofDays(14)));
        String forged = attacker.createAccessToken(42L);

        assertThat(provider().parse(forged)).isEmpty();
    }

    @Test
    @DisplayName("서명 바이트가 변조된 토큰은 모든 바이트 위치에서 거부한다")
    void rejectsTamperedToken() {
        JwtTokenProvider provider = provider();
        String token = provider.createAccessToken(42L);
        assertThat(provider.parse(token)).isPresent();

        // JWT의 마지막 Base64URL 글자에는 사용하지 않는 비트가 있을 수 있다. 문자열 끝을
        // 고정된 "xy"로 바꾸면 실제 서명 바이트가 그대로여서 발급 시각에 따라 테스트가 실패했다.
        // header/payload와 유효기간은 보존하고, 디코딩한 서명의 각 바이트에서 1비트를 뒤집는다.
        // 다시 표준 Base64URL로 인코딩하여 형식 오류가 아닌 실제 서명 불일치를 검증한다.
        int signatureStart = token.lastIndexOf('.') + 1;
        String signedContent = token.substring(0, signatureStart);
        byte[] signature = Base64.getUrlDecoder().decode(token.substring(signatureStart));
        assertThat(signature).isNotEmpty();
        for (int index = 0; index < signature.length; index++) {
            byte[] changed = signature.clone();
            changed[index] ^= 1;
            String tampered = signedContent + Base64.getUrlEncoder().withoutPadding().encodeToString(changed);
            assertThat(provider.parse(tampered)).as("서명 바이트 %d 변경은 거부해야 한다", index).isEmpty();
        }
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void rejectsExpiredToken() {
        // 음수 유효기간이면 발급 즉시 만료된 토큰이 나온다
        JwtTokenProvider provider = provider(Duration.ofSeconds(-1));

        String token = provider.createAccessToken(42L);

        assertThat(provider.parse(token)).isEmpty();
    }

    @Test
    @DisplayName("서명 키가 없거나 짧으면 기동 시점에 실패한다")
    void failsFastOnMissingOrShortSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(
                new JwtProperties("", Duration.ofMinutes(60), Duration.ofDays(14))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new JwtTokenProvider(
                new JwtProperties("too-short", Duration.ofMinutes(60), Duration.ofDays(14))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
