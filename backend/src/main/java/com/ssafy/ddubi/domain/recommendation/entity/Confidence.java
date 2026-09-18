package com.ssafy.ddubi.domain.recommendation.entity;

/**
 * 추천 신뢰도. {@code recommendation.confidence} 컬럼과 같은 값이다.
 *
 * <h3>무엇으로 정하나 — 가장 약한 구간이 전체를 정한다</h3>
 * 구간 열 개 중 아홉이 예측이어도 하나가 경로 API 면 그 경로의 답은 그만큼만 믿을 수 있다.
 * 평균을 내면 약한 구간이 숨는다.
 */
public enum Confidence {

    /** 모든 구간을 예측으로 계산했다. */
    HIGH,

    /** 예측을 못 쓴 구간이 있고 기준선으로 메웠다. */
    MID,

    /** 기준선조차 없어 경로 API 시간을 쓴 구간이 있다. */
    LOW
}
