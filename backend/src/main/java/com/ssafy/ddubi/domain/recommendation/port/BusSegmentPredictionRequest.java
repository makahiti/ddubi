package com.ssafy.ddubi.domain.recommendation.port;

import java.time.LocalDate;

/**
 * 버스 구간 소요시간 예측 요청 한 건.
 *
 * <h3>필드가 provider ID 인 이유</h3>
 * 예측 모델은 <b>학습에 쓴 서울 제공자 ID 체계</b>로 입력을 받는다(김종무 TIL, 2026-09-09).
 * 카카오 ID·정류장 이름·버스 번호를 그대로 넣으면 안 된다. 이 값들은
 * {@code route/mapping} 이 서울 마스터 데이터로 매핑해 낸 뒤의 것이다.
 *
 * <p>{@code segmentId} 는 모델 입력이 아니라 <b>응답을 되찾기 위한 우리 쪽 열쇠</b>다.
 *
 * @param fromStopSequence 해당 노선에서 출발 정류장의 순번
 * @param serviceDate      구간 <b>진입 예상</b> 날짜 (Asia/Seoul). 조회 시각이 아니다
 * @param hourSlot         구간 진입 예상 시간대 0~23
 */
public record BusSegmentPredictionRequest(
        String segmentId,
        String providerRouteId,
        String providerFromStopId,
        String providerToStopId,
        int fromStopSequence,
        LocalDate serviceDate,
        int hourSlot
) {
}
