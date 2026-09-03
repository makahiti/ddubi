package com.ssafy.ddubi.domain.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 회원가입 이메일 인증 상태 저장소 (Redis).
 *
 * <pre>
 * auth:email:code:{email}      발급한 코드            TTL 5분
 * auth:email:attempt:{email}   틀린 횟수              코드와 함께 사라진다
 * auth:email:cooldown:{email}  재발송 차단            TTL 60초
 * auth:email:verified:{email}  가입 단계로 넘길 통행증  TTL 30분
 * </pre>
 *
 * docs/contracts/redis-keys.yaml 계약.
 *
 * DB 가 아니라 Redis 인 이유: 전부 짧은 수명이라 TTL 로 알아서 사라져야 하고,
 * 만료된 코드를 청소하는 배치를 따로 두고 싶지 않다.
 *
 * 이메일은 <b>소문자로 정규화</b>해서 키를 만든다. 대소문자만 다른 주소로
 * 인증을 우회하는 경로를 막는다.
 */
@Component
public class EmailVerificationStore {

    private static final String CODE_KEY = "auth:email:code:";
    private static final String ATTEMPT_KEY = "auth:email:attempt:";
    private static final String COOLDOWN_KEY = "auth:email:cooldown:";
    private static final String VERIFIED_KEY = "auth:email:verified:";

    private final StringRedisTemplate redis;

    public EmailVerificationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 발송
    // ------------------------------------------------------------------

    /** 재발송 도배 차단. 쿨다운이 남아 있으면 true. */
    public boolean isOnCooldown(String email) {
        return Boolean.TRUE.equals(redis.hasKey(COOLDOWN_KEY + normalize(email)));
    }

    /** 코드를 저장하고 쿨다운을 건다. 이전 코드와 시도 횟수는 무효가 된다. */
    public void saveCode(String email, String code, Duration codeTtl, Duration cooldown) {
        String key = normalize(email);
        redis.opsForValue().set(CODE_KEY + key, code, codeTtl);
        redis.delete(ATTEMPT_KEY + key);
        redis.opsForValue().set(COOLDOWN_KEY + key, "1", cooldown);
    }

    // ------------------------------------------------------------------
    // 확인
    // ------------------------------------------------------------------

    /** 발급된 코드. 없으면 null(만료됐거나 발급된 적 없음). */
    public String findCode(String email) {
        return redis.opsForValue().get(CODE_KEY + normalize(email));
    }

    /**
     * 틀린 횟수를 하나 올리고 누적값을 돌려준다.
     * 6자리는 그냥 두면 대입으로 뚫린다 — 호출부가 이 값으로 상한을 건다.
     */
    public long increaseAttempt(String email) {
        String key = ATTEMPT_KEY + normalize(email);
        Long count = redis.opsForValue().increment(key);
        // 코드와 함께 사라지도록 수명을 맞춘다. 없으면 시도 횟수만 영원히 남는다.
        Long codeTtl = redis.getExpire(CODE_KEY + normalize(email));
        if (codeTtl != null && codeTtl > 0) {
            redis.expire(key, Duration.ofSeconds(codeTtl));
        }
        return count == null ? 0 : count;
    }

    /** 코드를 폐기한다. 인증 성공했거나 시도 횟수를 넘겼을 때. */
    public void discardCode(String email) {
        String key = normalize(email);
        redis.delete(CODE_KEY + key);
        redis.delete(ATTEMPT_KEY + key);
    }

    // ------------------------------------------------------------------
    // 통행증
    // ------------------------------------------------------------------

    public void markVerified(String email, Duration ttl) {
        redis.opsForValue().set(VERIFIED_KEY + normalize(email), "1", ttl);
    }

    /**
     * 통행증을 쓰고 없앤다.
     *
     * 조회가 아니라 소비인 이유: 한 번 인증한 이메일로 계정을 여러 개 만들거나,
     * 30분 동안 재사용하는 것을 막는다.
     *
     * @return 유효한 통행증이 있었으면 true
     */
    public boolean consumeVerified(String email) {
        return Boolean.TRUE.equals(redis.delete(VERIFIED_KEY + normalize(email)));
    }

    /** 소비하지 않고 보기만 한다. 테스트·진단용. */
    public boolean isVerified(String email) {
        return Boolean.TRUE.equals(redis.hasKey(VERIFIED_KEY + normalize(email)));
    }
}
