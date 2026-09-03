package com.ssafy.ddubi.domain.auth.entity;

/**
 * 로그인 수단. ENUM 값을 추가하려면 DDL 도 함께 고쳐야 한다(ALTER TABLE).
 *
 * SSAFY·APPLE 은 팀 합의로 제외했다 — ERD_master 기준 4종이 전부다.
 */
public enum AuthProvider {

    LOCAL("일반"),
    KAKAO("카카오"),
    GOOGLE("구글"),
    NAVER("네이버");

    private final String displayName;

    AuthProvider(String displayName) {
        this.displayName = displayName;
    }

    /** 사용자에게 보여줄 이름. 오류 안내 문구에 쓴다. */
    public String displayName() {
        return displayName;
    }

    public boolean isLocal() {
        return this == LOCAL;
    }
}
