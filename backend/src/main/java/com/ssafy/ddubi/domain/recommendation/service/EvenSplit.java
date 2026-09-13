package com.ssafy.ddubi.domain.recommendation.service;

/**
 * 외부 경로 API 가 준 step 시간을 edge 들에 <b>균등 분배</b>한다.
 *
 * <h3>합이 원본과 정확히 같아야 한다 (분산팀 조건, 2026-09-10)</h3>
 * 초 단위 정수로 저장하므로 그냥 나누면 버려진 나머지만큼 전체 시간이 줄어든다.
 * 900초를 7개로 나누면 128초씩 896초 — <b>4초가 사라진다.</b> 구간이 많은 경로에서는
 * 이 오차가 쌓여 출발 시각을 앞당기고, 그만큼 사용자가 늦는다.
 *
 * <p>그래서 나머지를 앞쪽 edge 부터 1초씩 더 준다(최대잉여법). 어느 edge 가 1초 더 받는지는
 * 임의지만, <b>합은 언제나 원본과 같다.</b>
 *
 * <h3>이 값은 관측이 아니다</h3>
 * 정류장 간 거리가 다르므로 실제 소요시간이 균등할 리 없다. 이건 "step 시간을 edge 에
 * 배치하는 방법" 이지 edge 를 측정한 값이 아니다. ML 학습 정답이나 기준선 표본으로
 * 쓰면 안 된다 — {@link EstimateSource#ROUTE_API_SPLIT} 참고.
 */
final class EvenSplit {

    private EvenSplit() {
    }

    /**
     * @param total 나눌 총 시간(초)
     * @param parts 나눌 개수. 1 이상
     * @return 길이가 {@code parts} 이고 <b>합이 정확히 {@code total}</b> 인 배열
     */
    static long[] distribute(long total, int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("나눌 개수는 1 이상이어야 합니다: " + parts);
        }
        long[] shares = new long[parts];
        long base = total / parts;
        long remainder = total % parts;

        for (int i = 0; i < parts; i++) {
            shares[i] = base + (i < remainder ? 1 : 0);
        }
        return shares;
    }
}
