package com.ssafy.ddubi.domain.report.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * ARRIVAL-001 도착 결과 저장.
 *
 * @param actualArrival  지오펜스가 잡은 실제 도착 시각. locationConsent 가 false 면 무시된다
 * @param locationConsent 이번 보고에 위치 기반 값을 쓸지. false 면 실제 도착을 저장하지 않는다.
 *                        사용자 계정의 동의(user.history_consented_at)와 <b>둘 다</b> 있어야 저장한다
 */
public record ArrivalRequest(
        LocalDateTime actualArrival,

        @NotNull(message = "locationConsent 는 필수입니다.")
        Boolean locationConsent
) {
}
