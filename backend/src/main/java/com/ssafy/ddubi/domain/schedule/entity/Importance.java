package com.ssafy.ddubi.domain.schedule.entity;

/**
 * 일정 중요도 — <b>일반 / 중요 두 단계</b> (2026-09-21 변경, B7).
 *
 * <h3>계산에 쓰지 않는다</h3>
 *
 * 저장하고 응답에 실어 보내지만, <b>추천 계산도 감시 주기도 알림 빈도도 이 값을 읽지 않는다.</b> 예전 주석은 "감시·재계산·알림 빈도에 쓴다" 고 적혀 있었는데 그런
 * 코드는 없었다 — 주석이 현실보다 앞서 있었다. 지금은 사용자에게 보이는 표시일 뿐이다.
 *
 * <p>여유시간을 정하던 시절에는 {@code NORMAL=p50 / HIGH=p80 / CRITICAL=p95} 였다. ML({@code traffic_t03})이
 * p50/p80/p95 를 아직 {@code null} 로 주기 때문에 떼어냈다. <b>커버리지 검증 전의 "p95" 는 이름만 p95</b> 다. 100번 중 95번이 그
 * 안에 들어온다는 보장이 없는 값으로 중요한 일정의 출발 시각을 정하면, 정확히 늦으면 안 되는 사람이 늦는다. ({@code
 * ddubi.recommendation.percentile.*} 설정도 같은 이유로 읽는 코드가 없다.)
 *
 * <h3>왜 세 단계가 아직 남아 있나</h3>
 *
 * {@link #CRITICAL} 은 <b>새로 저장되지 않는다</b>. 이미 저장된 행이 있어서 타입에만 남겨 둔다. DB 에서 지우려면 기존 데이터를 {@code
 * UPDATE} 해야 하는데 그건 되돌릴 수 없어 사용자 승인이 필요하다. 그때까지는 이 클래스가 경계에서 흡수한다:
 *
 * <ul>
 *   <li>들어올 때 — {@link #stored} 가 CRITICAL 을 HIGH 로 받는다. 400 으로 거절하지 않는다. 거절하면 앱이 업데이트될 때까지 일정 등록이
 *       막힌다.
 *   <li>나갈 때 — {@link #exposed} 가 도입 이전 CRITICAL 행을 HIGH 로 보여 준다.
 * </ul>
 *
 * <p>ML 백분위 커버리지 검증(종무 D3)이 끝나면 되살릴 수 있다. 자세한 배경은 BACKEND_CONVENTION.md 규칙 7.
 */
public enum Importance {

    /** 일반. */
    NORMAL,

    /** 중요. */
    HIGH,

    /**
     * 더 이상 저장하지 않는다. 2단계로 줄이기 전에 저장된 행에만 남아 있다.
     *
     * <p>새 코드에서 이 값을 쓰지 말 것. 읽을 때는 {@link #exposed} 가 HIGH 로 바꿔 준다.
     */
    CRITICAL;

    /**
     * DB 에 저장할 값. null 은 NORMAL 로, CRITICAL 은 HIGH 로 접는다.
     *
     * <p><b>미지정 기본값은 NORMAL 이다</b> — 프론트와 합의(2026-09-21). 앱은 {@code models.dart} 에서 오랫동안 {@code
     * high} 를 기본으로 보내고 있었는데, 그러면 아무것도 고르지 않은 일정이 전부 "중요" 가 되어 그 표시가 의미를 잃는다. 중요는 사용자가 일부러 고르는 값이다.
     * 앱 기본값도 normal 로 맞추기로 했다. <b>앱과 다르다는 이유로 이 기본값을 HIGH 로 되돌리지 말 것.</b>
     *
     * @param value 사용자가 보낸 값(null 가능)
     */
    public static Importance stored(Importance value) {
        return value == null || value == NORMAL ? NORMAL : HIGH;
    }

    /** 응답에 실을 값. 도입 이전 CRITICAL 행도 "중요" 로 보인다. */
    public Importance exposed() {
        return this == CRITICAL ? HIGH : this;
    }
}
