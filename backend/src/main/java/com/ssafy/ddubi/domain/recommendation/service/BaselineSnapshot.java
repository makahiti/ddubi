package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 번의 추천 계산이 쓰는 기준선 묶음. <b>계산 시작 때 한 번 읽어 끝까지 들고 간다.</b>
 *
 * <h3>왜 스냅샷인가</h3>
 * 두 가지를 동시에 해결한다.
 * <ul>
 *   <li><b>1 + N 방지</b> — 구간마다 물으면 경로 하나에 수십 번 왕복한다</li>
 *   <li><b>일관성</b> — 계산 도중 activate 가 일어나도 구간마다 다른 버전을 섞지 않는다.
 *       섞이면 그 추천을 나중에 재현할 수 없다</li>
 * </ul>
 */
public final class BaselineSnapshot {

    private static final BaselineSnapshot EMPTY = new BaselineSnapshot(Map.of());

    private final Map<Key, Integer> p50ByKey;

    private BaselineSnapshot(Map<Key, Integer> p50ByKey) {
        this.p50ByKey = p50ByKey;
    }

    /** 기준선이 아직 하나도 없을 때. 지금(2026-09-10) 실제 상태다. */
    public static BaselineSnapshot empty() {
        return EMPTY;
    }

    public static BaselineSnapshot of(List<BaselineServing> rows) {
        Map<Key, Integer> map = new HashMap<>();
        for (BaselineServing row : rows) {
            map.put(new Key(row.getSegmentId(), row.getDow(), row.getHourSlot()), row.getP50());
        }
        return new BaselineSnapshot(map);
    }

    /** @return 평소값(초). 그 구간·요일·시간대의 기준선이 없으면 null */
    public Long p50(String segmentId, byte dow, byte hourSlot) {
        Integer value = p50ByKey.get(new Key(segmentId, dow, hourSlot));
        return value == null ? null : value.longValue();
    }

    public boolean isEmpty() {
        return p50ByKey.isEmpty();
    }

    private record Key(String segmentId, byte dow, byte hourSlot) {
    }
}
