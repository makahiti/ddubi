package com.ssafy.ddubi.domain.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 리프레시 세션 저장소 (Redis).
 *
 * 키: {@code auth:refresh:{userId}:{deviceId}} — docs/contracts/redis-keys.yaml 계약.
 * 값: 현재 유효한 Refresh 토큰의 jti 하나. 회전할 때마다 덮어쓴다.
 *
 * <h3>회전과 재사용 감지</h3>
 * 클라이언트가 Refresh 로 재발급을 요청하면 새 jti 로 즉시 교체한다.
 * 이때 들고 온 jti 가 저장된 값과 다르면 <b>이미 회전된 옛 토큰</b>이라는 뜻이고,
 * 이는 토큰이 탈취되어 두 곳에서 쓰이고 있다는 신호다. 이 경우 해당 사용자의
 * 세션을 전부 폐기해 공격자와 정상 사용자를 함께 로그아웃시킨다(7장 계약).
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String KEY_PREFIX = "auth:refresh:";

    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(Long userId, String deviceId) {
        return KEY_PREFIX + userId + ":" + deviceId;
    }

    /** 로그인·회전 시 현재 jti 를 저장한다. TTL 은 Refresh 유효기간과 맞춘다. */
    public void save(Long userId, String deviceId, String tokenId, Duration ttl) {
        redis.opsForValue().set(key(userId, deviceId), tokenId, ttl);
    }

    /** 기존 jti 검증과 새 jti 저장을 Redis 안에서 한 번에 처리한다. */
    public boolean rotate(Long userId, String deviceId, String expectedTokenId,
                          String newTokenId, Duration ttl) {
        Long result = redis.execute(ROTATE_SCRIPT, List.of(key(userId, deviceId)),
                expectedTokenId, newTokenId, String.valueOf(ttl.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    public boolean exists(Long userId, String deviceId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId, deviceId)));
    }

    /** 로그아웃 — 해당 기기 세션만 지운다. */
    public void revoke(Long userId, String deviceId) {
        redis.delete(key(userId, deviceId));
    }

    /**
     * 재사용 감지·탈퇴 시 사용자의 모든 기기 세션을 폐기한다.
     *
     * KEYS 대신 SCAN 을 쓴다 — KEYS 는 매칭이 끝날 때까지 Redis 를 블로킹하고,
     * 이 Redis 에는 Spark 가 쓰는 구간 상태도 함께 들어 있어 서비스 전체가 멈출 수 있다.
     */
    public long revokeAll(Long userId) {
        String pattern = KEY_PREFIX + userId + ":*";
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        log.warn("사용자 {} 의 리프레시 세션 {}건 폐기", userId, removed);
        return removed == null ? 0 : removed;
    }
}
