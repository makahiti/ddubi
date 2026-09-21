package com.ssafy.ddubi.domain.place.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** PLACE-003 장소 등록. 수도권·부산 밖 좌표는 서비스 계층이 422 로 거절한다. */
public record PlaceCreateRequest(
        @NotBlank(message = "장소 이름은 필수입니다.") @Size(max = 50, message = "장소 이름은 50자를 넘을 수 없습니다.")
                String name,
        @NotBlank(message = "주소는 필수입니다.") @Size(max = 255, message = "주소는 255자를 넘을 수 없습니다.")
                String address,
        @NotNull(message = "위도는 필수입니다.") @DecimalMin(value = "-90") @DecimalMax(value = "90")
                BigDecimal lat,
        @NotNull(message = "경도는 필수입니다.") @DecimalMin(value = "-180") @DecimalMax(value = "180")
                BigDecimal lng) {}
