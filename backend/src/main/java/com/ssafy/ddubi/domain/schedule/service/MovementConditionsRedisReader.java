package com.ssafy.ddubi.domain.schedule.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이동 변수 Redis 읽기를 한곳에 모은다. 지하철 공지는 아직 호선별 인덱스 계약이 없으므로 전체 서버를 멈출 수 있는 {@code KEYS} 대신 제한된 {@code
 * SCAN}을 사용한다. 다른 메서드는 계약에 명시된 정확한 key/hash field만 읽는다.
 */
@Component
public class MovementConditionsRedisReader {
    private final StringRedisTemplate redis;

    public MovementConditionsRedisReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String value(String key) {
        return redis.opsForValue().get(key);
    }

    public String hashValue(String key, String field) {
        Object value = redis.opsForHash().get(key, field);
        return value == null ? null : String.valueOf(value);
    }

    public Map<Object, Object> hashEntries(String key) {
        return redis.opsForHash().entries(key);
    }

    /** prefix에 속한 문자열 값을 최대 limit개 읽는다. limit은 Redis 장애 시 응답 비용의 상한이다. */
    public List<KeyValue> scanValues(String prefix, int limit) {
        List<String> keys =
                redis.execute(
                        (RedisCallback<List<String>>)
                                connection -> {
                                    List<String> found = new ArrayList<>();
                                    ScanOptions options =
                                            ScanOptions.scanOptions()
                                                    .match(prefix + "*")
                                                    .count(100)
                                                    .build();
                                    try (Cursor<byte[]> cursor =
                                            connection.keyCommands().scan(options)) {
                                        while (cursor.hasNext() && found.size() < limit) {
                                            found.add(
                                                    new String(
                                                            cursor.next(), StandardCharsets.UTF_8));
                                        }
                                    }
                                    return found;
                                });
        if (keys == null || keys.isEmpty()) return List.of();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) return List.of();
        List<KeyValue> result = new ArrayList<>();
        for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
            if (values.get(i) != null) result.add(new KeyValue(keys.get(i), values.get(i)));
        }
        return List.copyOf(result);
    }

    public record KeyValue(String key, String value) {}
}
