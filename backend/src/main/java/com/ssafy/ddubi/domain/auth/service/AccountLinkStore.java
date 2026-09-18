package com.ssafy.ddubi.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 계정 연동 티켓 (Redis). 키: {@code auth:link:{linkToken}}
 *
 * <h3>왜 소셜 토큰을 화면 사이로 돌리지 않나</h3>
 * "기존 계정을 찾았다" 화면과 "비밀번호 확인" 화면 사이에 소셜 액세스 토큰을 들고 다니면,
 * 그 토큰이 <b>프론트 저장소·로그·URL</b> 어딘가에 남는다. 그건 그 소셜 계정으로 무엇이든
 * 할 수 있는 값이다. 대신 <b>불투명한 일회용 티켓</b>만 내보내고, 누구를 어디에 붙일지는
 * 서버가 Redis 에 들고 있는다.
 *
 * <h3>짧고, 한 번만</h3>
 * TTL 5분. 소비할 때 {@code GETDEL} 로 <b>읽으면서 지운다</b> — 읽고 나서 지우면 그 사이에
 * 같은 티켓으로 두 번 들어올 수 있다. 티켓 하나로 두 번 연동되면 두 번째는 이미 연결된
 * 소셜을 또 붙이려다 UNIQUE 제약에 걸려 500 이 된다.
 *
 * <p>비밀번호가 틀린 경우에도 티켓은 소비된다. 그래야 이 티켓이 비밀번호 대입 시도의
 * 창구가 되지 않는다 — 다시 하려면 소셜 로그인부터 거쳐야 한다.
 */
@Component
public class AccountLinkStore {

    private static final String KEY_PREFIX = "auth:link:";

    /** 사용자가 비밀번호를 찾아 입력하기에 충분하고, 방치된 티켓이 오래 남지 않는 길이. */
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public AccountLinkStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 연동할 대상을 담아 두고 티켓을 발급한다.
     *
     * @param userId         비밀번호를 확인할 <b>기존</b> 사용자
     * @param provider       붙이려는 소셜
     * @param providerUserId 그 소셜의 회원번호
     */
    public String issue(Long userId, AuthProvider provider, String providerUserId) {
        String token = newToken();
        Ticket ticket = new Ticket(userId, provider, providerUserId);
        try {
            redis.opsForValue().set(KEY_PREFIX + token, objectMapper.writeValueAsString(ticket), TTL);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "연동 요청을 만들지 못했습니다.");
        }
        return token;
    }

    /**
     * 티켓을 <b>읽으면서 지운다</b>. 없거나 이미 썼으면 빈 값이다.
     *
     * <p>{@code GETDEL} 을 쓰는 이유: 조회와 삭제를 나누면 두 요청이 동시에 같은 티켓을
     * 읽어 둘 다 통과한다.
     */
    public Optional<Ticket> consume(String linkToken) {
        if (linkToken == null || linkToken.isBlank()) {
            return Optional.empty();
        }
        String raw = redis.opsForValue().getAndDelete(KEY_PREFIX + linkToken);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, Ticket.class));
        } catch (JsonProcessingException e) {
            // 값이 깨졌으면 만료와 같게 취급한다. 억지로 복구하는 것보다 다시 시작하는 편이 안전하다.
            return Optional.empty();
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 티켓에 담기는 것. <b>소셜 액세스 토큰은 담지 않는다.</b> */
    public record Ticket(Long userId, AuthProvider provider, String providerUserId) {
    }
}
