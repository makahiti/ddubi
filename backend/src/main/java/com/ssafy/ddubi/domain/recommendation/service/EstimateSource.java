package com.ssafy.ddubi.domain.recommendation.service;

/**
 * 구간 소요시간을 어디서 얻었는가. 추천 응답과 {@code reasons} JSON 에 그대로 남는다.
 *
 * <h3>어느 값도 학습 정답이 아니다</h3>
 * 여기 있는 값은 전부 <b>서빙을 위해 만든 추정치</b>다. 이걸 다시 ML 학습 정답이나
 * {@code baseline_serving} 표본으로 넣으면 모델이 자기 출력을 배우게 된다.
 * 특히 {@link #ROUTE_API_SPLIT} 은 관측이 아니라 <b>배분 결과</b>라서 더 그렇다.
 * (분산팀 확인, 2026-09-10)
 */
public enum EstimateSource {

    /** 예측 서비스(Python). 진입 예상시각 기준의 버스 구간 소요시간. */
    PREDICTION,

    /** {@code baseline_serving} 의 평소값. 예측이 없거나 그 step 을 못 맞출 때. */
    BASELINE,

    /**
     * 외부 경로 API 가 준 시간을 <b>그대로</b> 쓴 경우.
     *
     * <p>도보는 항상 여기다 — ML 결정 문서(2026-09-08)가 일반 도보를 학습 대상에서 빼고
     * 경로 API 시간을 쓰기로 확정했다. 폴백이 아니라 정해진 출처다.
     * 매핑에 실패해 edge 가 없는 step 도 여기로 온다.
     */
    ROUTE_API,

    /**
     * 경로 API 의 step 시간을 edge 들에 <b>균등 분배</b>한 값.
     *
     * <p>{@link #ROUTE_API} 와 굳이 나눈 이유: 이건 측정도 추정도 아니고 <b>배치 방법</b>이다.
     * 정류장 간 거리가 다른데 같은 시간을 준 것이라, 개별 edge 값은 사실이 아니다.
     * 합만 원본과 같다. 나중에 이 값을 보고 "구간 A 가 3분 걸렸다" 고 읽으면 안 된다.
     */
    ROUTE_API_SPLIT;

    /**
     * 실제 관측·모델 출력이 아니라 <b>우리가 만들어 낸 배분값</b>인가.
     * 지표 집계나 표본 수집에서 걸러 내는 용도다.
     */
    public boolean isSynthetic() {
        return this == ROUTE_API_SPLIT;
    }
}
