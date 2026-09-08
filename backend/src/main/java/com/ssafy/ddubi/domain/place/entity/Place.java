package com.ssafy.ddubi.domain.place.entity;

import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 자주 쓰는 장소 (즐겨찾기).
 *
 * schedule 은 이 테이블을 FK 참조하지 않는다 — 일정 생성 시 이름·좌표를 **값 복사**한다
 * (도메인 규칙 2). 그래서 장소를 수정·삭제해도 기존 일정은 그대로다.
 */
@Entity
@Table(name = "place")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 별칭 (예: 집, 회사). */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal lng;

    @Builder
    private Place(User user, String name, String address, BigDecimal lat, BigDecimal lng) {
        this.user = user;
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    public void changeName(String name) {
        this.name = name;
    }

    public void changeAddress(String address) {
        this.address = address;
    }

    public void changeCoordinate(BigDecimal lat, BigDecimal lng) {
        this.lat = lat;
        this.lng = lng;
    }
}
