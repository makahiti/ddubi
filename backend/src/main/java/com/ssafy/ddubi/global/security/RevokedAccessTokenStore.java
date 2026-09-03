package com.ssafy.ddubi.global.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 사용자별 액세스 토큰 세대 저장소 (Redis).
 *
 * <h3>왜 필요한가</h3>
 * JWT 는 자체 검증이라 서버가 "이 토큰 취소"라고 말할 방법이 없다. 리프레시 세션을 지워도
 * 이미 발급된 <b>액세스 토큰은 만료(60분) 전까지 그대로 통과</b>한다.
 *
 * 탈퇴한 사용자의 토큰이 살아 있으면 단순히 조회가 되는 정도가 아니라,
 * 쓰기 API 에서 사라진 user_id 로 INSERT 를 시도해 FK 위반 500 이 난다.
 *
 * <h3>동작</h3>
 * 무효화할 때 세대 번호를 올린다. 이후 로그인은 현재 세대로 토큰을 발급하므로 정상 동작하고,
 * 이전 세대 토큰만 만료 시각까지 계속 거부된다. 새 토큰을 발급할 때 TTL 을 다시 늘려
 * 현재 세대 토큰보다 Redis 키가 먼저 사라지지 않게 한다.
 *
 * 키: {@code auth:revoked:{userId}} — docs/contracts/redis-keys.yaml
 */
@Component
public class RevokedAccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessTokenStore.class);

    private static final String KEY_PREFIX = "auth:revoked:";
    private static final DefaultRedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
            local version = redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return version
            """, Long.class);

    private static final DefaultRedisScript<Long> ISSUE_VERSION_SCRIPT = new DefaultRedisScript<>("""
            local version = redis.call('GET', KEYS[1])
            if not version then
              return 0
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return tonumber(version)
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RevokedAccessTokenStore(StringRedisTemplate redis, JwtProperties jwtProperties) {
        this.redis = redis;
        // 액세스 토큰이 살아 있는 동안만 막으면 된다.
        this.ttl = jwtProperties.accessTokenValidity();
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /** 탈퇴·토큰 탈취 감지 시 호출. 현재보다 이전 세대 액세스 토큰을 즉시 차단한다. */
    public long revoke(Long userId) {
        Long version = redis.execute(REVOKE_SCRIPT, List.of(key(userId)),
                String.valueOf(ttl.toMillis()));
        long revokedVersion = version == null ? 0 : version;
        log.info("액세스 토큰 세대 변경 — userId={}, version={} ({}분간)",
                userId, revokedVersion, ttl.toMinutes());
        return revokedVersion;
    }

    /** 토큰의 세대가 현재 사용자 세대와 다르면 과거에 발급된 토큰이다. */
    public boolean isRevoked(Long userId, long tokenVersion) {
        return currentVersion(userId) != tokenVersion;
    }

    /** 새 액세스 토큰에 넣을 현재 세대. 새 토큰 수명만큼 키 TTL 도 연장한다. */
    public long versionForIssue(Long userId) {
        Long version = redis.execute(ISSUE_VERSION_SCRIPT, List.of(key(userId)),
                String.valueOf(ttl.toMillis()));
        return version == null ? 0 : version;
    }

    public long currentVersion(Long userId) {
        String value = redis.opsForValue().get(key(userId));
        return value == null ? 0 : Long.parseLong(value);
    }
}
