package com.ssafy.ddubi.domain.recommendation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStateReader;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code segment:status:{segmentId}} 를 읽는다 (redis-keys.yaml, owner=spark).
 *
 * <h3>값이 깨져도 추천은 계속된다</h3>
 * 스키마가 아직 확정 전이라(계약 파일에 "분산 담당 확정 대기") 우리가 모르는 모양이 올 수 있다.
 * 그때 예외를 던지면 <b>실시간 보정 하나 때문에 추천 전체가 실패한다.</b> 보정은 있으면 좋은
 * 것이지 없으면 안 되는 것이 아니라서, 파싱 실패는 로그만 남기고 그 구간을 건너뛴다.
 *
 * <h3>MGET 으로 한 번에</h3>
 * 경로 하나에 구간이 수십 개다. 구간마다 GET 하면 추천 한 번에 왕복이 그만큼 쌓인다.
 */
@Component
public class RedisSegmentStateReader implements SegmentStateReader {

    private static final Logger log = LoggerFactory.getLogger(RedisSegmentStateReader.class);
    private static final String KEY_PREFIX = "segment:status:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSegmentStateReader(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, SegmentStatus> readAll(Collection<String> segmentIds) {
        Map<String, SegmentStatus> found = new LinkedHashMap<>();
        if (segmentIds == null || segmentIds.isEmpty()) {
            return found;
        }

        List<String> ids = segmentIds.stream().distinct().toList();
        List<String> keys = ids.stream().map(id -> KEY_PREFIX + id).toList();

        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            // Redis 가 죽어도 기준선·경로 API 로 추천은 나가야 한다.
            log.warn("구간 상태 조회 실패 — 실시간 보정 없이 진행합니다: {}", e.getMessage());
            return found;
        }
        if (values == null) {
            return found;
        }

        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            String raw = values.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            parse(ids.get(i), raw).ifPresent(status -> found.put(status.segmentId(), status));
        }
        return found;
    }

    private java.util.Optional<SegmentStatus> parse(String segmentId, String raw) {
        try {
            var node = objectMapper.readTree(raw);
            return java.util.Optional.of(new SegmentStatus(
                    segmentId,
                    node.path("status").asText("unknown"),
                    node.path("delaySeconds").asInt(0),
                    node.hasNonNull("confidence") ? node.get("confidence").asDouble() : null,
                    instant(node.path("computedAt").asText(null)),
                    instant(node.path("validUntil").asText(null)),
                    node.path("source").asText(null)));
        } catch (Exception e) {
            log.warn("구간 상태 값을 해석하지 못했습니다 segmentId={} — 이 구간은 보정 없이 갑니다: {}",
                    segmentId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
