package com.ssafy.ddubi.domain.route.mapping;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;

/**
 * 활성 마스터 버전에서 검증된 외부 표기 → 공식 ID 후보 집합 별칭 스냅샷이다.
 *
 * <p>값은 이름이 아니라 마스터의 정적 canonical ID다. 따라서 별칭이 공식 마스터 이름
 * 인덱스까지 오염시키거나 동명이 정류장을 잘못 합치지 않는다. 환승역과 동명 정류장은
 * 노선별 canonical ID가 다를 수 있으므로 값은 단일 ID가 아니라 집합으로 보존하고 Matcher가
 * 현재 노선/호선 topology 안에서 다시 제한한다. DB 행을 요청마다 조회하지 않고 마스터
 * reload 시 불변 Map으로 교체한다.</p>
 */
public record TransitAliasSnapshot(
        Map<String, Set<String>> busRouteAliasIds,
        Map<String, Set<String>> busStopAliasIds,
        Map<String, Set<String>> subwayLineAliasIds,
        Map<String, Set<String>> subwayStationAliasIds
) {
    public TransitAliasSnapshot {
        busRouteAliasIds = immutable(busRouteAliasIds);
        busStopAliasIds = immutable(busStopAliasIds);
        subwayLineAliasIds = immutable(subwayLineAliasIds);
        subwayStationAliasIds = immutable(subwayStationAliasIds);
    }

    public static TransitAliasSnapshot empty() {
        return new TransitAliasSnapshot(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 같은 dataVersion 안에서 별칭 검증 상태가 바뀌어도 과거 EXACT 캐시를 재사용하지 않게 한다.
     * 정렬된 alias→ID 전체를 해시하므로 DB 조회 순서와 무관하게 같은 스냅샷은 같은 토큰을 만든다.
     */
    public String policyFingerprint() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "BR", busRouteAliasIds);
        append(canonical, "BS", busStopAliasIds);
        append(canonical, "SL", subwayLineAliasIds);
        append(canonical, "SS", subwayStationAliasIds);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            // 캐시 정책 열을 짧게 유지하면서도 운영상 충돌 가능성을 충분히 낮춘다.
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM에 SHA-256 구현이 없습니다.", exception);
        }
    }

    private static Map<String, Set<String>> immutable(Map<String, Set<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        values.forEach((alias, ids) -> {
            if (alias == null || alias.isBlank() || ids == null || ids.isEmpty()
                    || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("별칭과 canonical ID 집합은 비어 있을 수 없습니다.");
            }
            result.put(alias, Set.copyOf(ids));
        });
        return Map.copyOf(result);
    }

    private static void append(
            StringBuilder target,
            String type,
            Map<String, Set<String>> values
    ) {
        values.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> entry.getValue().stream().sorted()
                        .forEach(id -> target.append(type).append('\u0000')
                                .append(entry.getKey()).append('\u0000')
                                .append(id).append('\u0001')));
    }
}
