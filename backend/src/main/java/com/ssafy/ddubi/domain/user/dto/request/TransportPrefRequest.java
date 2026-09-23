package com.ssafy.ddubi.domain.user.dto.request;

import com.ssafy.ddubi.domain.user.entity.TransportMode;
import com.ssafy.ddubi.domain.user.entity.TransportPreference;
import jakarta.validation.constraints.NotNull;

/**
 * 교통수단 선호·회피 한 건 (REQ-SCH-004).
 *
 * 경로 도메인에도 같은 이름의 TransportMode 가 있다. 이쪽은 사용자 취향용이라
 * WALK 가 없고 BUS·SUBWAY 만 있다 — import 를 헷갈리지 말 것.
 */
public record TransportPrefRequest(
        @NotNull(message = "mode 는 필수입니다.")
        TransportMode mode,

        @NotNull(message = "preference 는 필수입니다.")
        TransportPreference preference
) {
}
