package com.ssafy.ddubi.domain.recommendation.port;

import java.util.List;
import java.util.Map;

/**
 * 버스 구간 소요시간 예측 서비스(Python). 경로 하나에 버스 구간이 여러 개라
 * <b>배치로 한 번에</b> 부른다 — 분산팀이 그 형태로 계약을 맞추기로 했다(2026-09-10).
 *
 * <h3>없어도 서비스는 돈다</h3>
 * 데모까지 이 서비스가 뜬다는 확약이 없다. 그래서 기본 구현은
 * {@link com.ssafy.ddubi.domain.recommendation.adapter.UnavailableBusTravelTimePredictor}
 * 이고, 추천은 기준선·경로 API 로 내려간다. 예측이 붙으면 구현체만 바뀐다.
 */
public interface BusTravelTimePredictor {

    /**
     * @return 요청의 {@code segmentId} 를 키로 하는 결과. <b>모든 요청에 대해 항목이 있어야 한다</b> —
     *         예측 못 한 구간도 {@code UNAVAILABLE} 로 담는다. 키가 통째로 빠지면
     *         "왜 폴백했는지" 를 나중에 설명할 수 없다
     */
    Map<String, BusTravelTimePrediction> predict(List<BusSegmentPredictionRequest> requests);
}
