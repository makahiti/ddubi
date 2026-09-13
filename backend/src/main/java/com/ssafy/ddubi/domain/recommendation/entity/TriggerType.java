package com.ssafy.ddubi.domain.recommendation.entity;

/**
 * 이 추천을 만든 계기. 같은 일정에 여러 행이 쌓이므로, 나중에 목록을 보면
 * "사용자가 눌러서 다시 계산한 것" 과 "교통 변화로 서버가 다시 계산한 것" 이 구분돼야 한다.
 */
public enum TriggerType {

    /** 최초 조회. */
    INITIAL,

    /** 사용자가 새로고침했다. */
    REFRESH,

    /** 교통 변화를 감지해 서버가 다시 계산했다. */
    EVENT
}
