package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.route.model.TransportMode;

import java.util.List;

/**
 * 산정의 <b>판단 단위</b>. 외부 경로 API 가 주는 이동 구간 하나(카카오 step)다.
 *
 * <h3>왜 edge 가 아니라 step 이 단위인가</h3>
 * 분산팀 합의(2026-09-10): <b>한 step 안에서 일부 edge 만 ML 값으로 바꾸지 않는다.</b>
 * 섞으려면 별도 정책과 검증이 필요하다. 예를 들어 step 시간을 균등 분배할 때, edge 다섯 중
 * 셋이 이미 다른 출처를 쓰고 있으면 남은 둘에 나눌 시간이 얼마인지 정할 근거가 없다 —
 * step 시간은 다섯 개 전체를 덮는 값이다.
 *
 * <p>그래서 출처는 step 단위로 하나만 고르고, 그 안의 edge 는 같은 출처를 쓴다.
 *
 * @param routeApiSeconds 외부 경로 API 가 준 이 step 의 시간(초). 마지막 보루라 항상 있어야 한다
 * @param edges           전개된 인접 구간들. 도보이거나 매핑 실패면 비어 있다
 */
public record StepEstimateInput(
        int sequence,
        TransportMode mode,
        long routeApiSeconds,
        List<EdgeEstimateInput> edges
) {

    public StepEstimateInput {
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** 매핑이 없는 step. 도보이거나 서울 마스터에 못 맞춘 경우다. */
    public boolean hasNoEdges() {
        return edges.isEmpty();
    }

    /**
     * 이 step 전체를 예측으로 계산해 볼 수 있는가.
     * <b>edge 하나라도 예측 대상이 아니면 step 전체가 아니다</b> — 부분 혼합 금지.
     */
    public boolean isPredictable() {
        return mode == TransportMode.BUS
                && !edges.isEmpty()
                && edges.stream().allMatch(edge -> edge.busKey() != null && edge.segmentId() != null);
    }
}
