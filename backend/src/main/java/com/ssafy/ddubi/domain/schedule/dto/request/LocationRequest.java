package com.ssafy.ddubi.domain.schedule.dto.request;

import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 출발지·목적지 공통 입력. 이름·좌표를 스냅샷으로 복사한다(도메인 규칙 2). */
public record LocationRequest(
        @NotBlank(message = "장소 이름은 필수입니다.")
        @Size(max = 100, message = "장소 이름은 100자를 넘을 수 없습니다.")
        String name,

        @NotNull(message = "위도는 필수입니다.")
        @DecimalMin(value = "-90") @DecimalMax(value = "90")
        BigDecimal lat,

        @NotNull(message = "경도는 필수입니다.")
        @DecimalMin(value = "-180") @DecimalMax(value = "180")
        BigDecimal lng
) {

    public LocationSnapshot toSnapshot() {
        return new LocationSnapshot(name, lat, lng);
    }
}
