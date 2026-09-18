package com.ssafy.ddubi.domain.recommendation.service;

import java.time.Instant;

/**
 * 산정 결과 한 줄. step 에 edge 가 여럿이면 edge 마다 한 줄이다.
 *
 * <p><b>왜 이 숫자가 나왔는지</b>를 같이 들고 다닌다 — 추천 화면의 "이유" 와 재현성
 * 스냅샷이 이 값으로 만들어진다.
 *
 * @param stepSequence      외부 경로 API 가 준 이동 구간 순서 (1부터)
 * @param edgeSeq           그 step 안의 인접구간 순서 (1부터). edge 가 없으면 0
 * @param segmentId         공용 인접구간 ID. 도보·미매핑이면 null
 * @param baseSeconds       출처에서 얻은 기본 소요시간
 * @param adjustmentSeconds 실시간 상태로 더한 지연(초). 보정을 안 썼으면 0
 * @param note              폴백했다면 그 이유와 방식. 정상 경로면 null
 */
public record SegmentEstimate(
        int stepSequence,
        int edgeSeq,
        String segmentId,
        Instant enteredAt,
        long baseSeconds,
        EstimateSource source,
        long adjustmentSeconds,
        String note
) {

    public long totalSeconds() {
        return baseSeconds + adjustmentSeconds;
    }

    public boolean isAdjusted() {
        return adjustmentSeconds != 0;
    }

    /**
     * 실제 관측·모델 출력이 아니라 배분해서 만든 값인가.
     * 지표나 표본으로 쓰기 전에 반드시 걸러야 한다.
     */
    public boolean isSynthetic() {
        return source.isSynthetic();
    }
}
