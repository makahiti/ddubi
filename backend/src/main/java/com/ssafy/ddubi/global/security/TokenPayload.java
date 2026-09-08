package com.ssafy.ddubi.global.security;

/**
 * 파싱된 토큰 내용.
 *
 * @param userId   subject. 인증된 사용자 식별자
 * @param type     ACCESS / REFRESH
 * @param deviceId 기기 식별자. REFRESH 만 가진다 — Redis 키 auth:refresh:{userId}:{deviceId} 에 쓴다
 * @param tokenId  jti. 회전 시 재사용 감지에 쓴다(REFRESH 만)
 * @param accessVersion 액세스 토큰 세대. 재사용 감지 이전에 발급된 토큰만 계속 차단한다
 */
public record TokenPayload(
        Long userId,
        TokenType type,
        String deviceId,
        String tokenId,
        long accessVersion
) {
}
