package com.ssafy.ddubi.domain.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 장소 스냅샷 — 이름·좌표 값 복사 (도메인 규칙 2).
 *
 * place 를 FK 참조하지 않는 이유: 장소를 수정·삭제해도 이미 만든 일정은
 * 그대로여야 한다(REQ-SCH-006). schedule 과 schedule_repeat 이 같은 구조를 쓴다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationSnapshot {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal lng;

    public LocationSnapshot(String name, BigDecimal lat, BigDecimal lng) {
        this.name = name;
        this.lat = lat;
        this.lng = lng;
    }
}
