package com.ssafy.ddubi.global.security;

/**
 * 토큰 용도. 클레임에 박아두고 검증 시 대조한다.
 *
 * 이걸 구분하지 않으면 유효기간이 14일인 Refresh 토큰을 Authorization 헤더에 넣어
 * API 를 호출할 수 있다 — 탈취 시 피해 범위가 14일로 늘어난다.
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
