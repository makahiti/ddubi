package com.ssafy.ddubi.domain.place.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PLACE-005 부분 수정.
 *
 * record 가 아니라 presence 플래그를 가진 클래스다 — "필드가 없음"과 "명시적 null"을
 * 구분해야 하기 때문이다({@code UserUpdateRequest} 와 같은 이유). 좌표는 둘 중 하나만
 * 오면 반쪽 이동이 되므로 서비스에서 쌍으로 검증한다.
 */
public class PlaceUpdateRequest {

    @Size(min = 1, max = 50, message = "장소 이름은 1~50자여야 합니다.")
    private String name;
    private boolean namePresent;

    @Size(max = 255, message = "주소는 255자를 넘을 수 없습니다.")
    private String address;
    private boolean addressPresent;

    @DecimalMin(value = "-90") @DecimalMax(value = "90")
    private BigDecimal lat;
    private boolean latPresent;

    @DecimalMin(value = "-180") @DecimalMax(value = "180")
    private BigDecimal lng;
    private boolean lngPresent;

    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public void setAddress(String address) {
        this.address = address;
        this.addressPresent = true;
    }

    public void setLat(BigDecimal lat) {
        this.lat = lat;
        this.latPresent = true;
    }

    public void setLng(BigDecimal lng) {
        this.lng = lng;
        this.lngPresent = true;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    public boolean isAddressPresent() {
        return addressPresent;
    }

    public boolean isLatPresent() {
        return latPresent;
    }

    public boolean isLngPresent() {
        return lngPresent;
    }

    public boolean isEmpty() {
        return !namePresent && !addressPresent && !latPresent && !lngPresent;
    }
}
