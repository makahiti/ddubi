package com.ssafy.ddubi.domain.notification.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 알림 재발송 방지 (Redis). 키: {@code notify:suppress:{userId}:{suppressKey}}
 *
 * <h3>왜 Redis 인가</h3>
 * "최근에 같은 알림을 보냈나" 는 짧은 수명의 질문이다. TTL 로 알아서 사라져야 하고,
 * 지난 판정을 청소하는 배치를 두고 싶지 않다. notification 테이블을 조회해도 되지만
 * 알림마다 시간 범위 쿼리가 붙는다.
 *
 * <h3>선점 방식</h3>
 * {@code SET NX} 로 <b>없을 때만</b> 넣는다. 조회 후 저장으로 나누면 재계산이 동시에
 * 두 번 돌 때 같은 알림이 둘 다 통과한다 — 사용자에게 같은 푸시가 두 번 간다.
 */
@Component
public class SuppressionStore {

    private static final String KEY_PREFIX = "notify:suppress:";

    private final StringRedisTemplate redis;

    public SuppressionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(Long userId, String suppressKey) {
        return KEY_PREFIX + userId + ":" + suppressKey;
    }

    /**
     * 발송 자리를 선점한다.
     *
     * @return true 면 이번이 처음이라 보내도 된다. false 면 TTL 안에 이미 보냈다
     */
    public boolean tryAcquire(Long userId, String suppressKey, Duration ttl) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key(userId, suppressKey), "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 선점을 되돌린다. 행은 만들었지만 발송이 실패했을 때 호출한다 —
     * 자리를 잡아둔 채로 두면 TTL 동안 재시도조차 막힌다.
     */
    public void release(Long userId, String suppressKey) {
        redis.delete(key(userId, suppressKey));
    }
}
