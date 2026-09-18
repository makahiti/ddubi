package com.ssafy.ddubi.domain.recommendation.port;

import java.util.Collection;
import java.util.Map;

/**
 * 구간별 실시간 상태를 읽는다. 이름과 역할은 {@code docs/contracts/redis-keys.yaml} 에
 * 명시된 계약이다 — "값 스키마는 분산 담당 확정 대기, 백엔드는 어댑터로 격리한다".
 *
 * <p>인터페이스로 두는 이유가 그것이다. Redis 값의 모양이 바뀌어도 구현체 하나만 고친다.
 */
public interface SegmentStateReader {

    /**
     * 여러 구간을 <b>한 번에</b> 읽는다. 경로 하나에 구간이 수십 개라
     * 구간마다 왕복하면 추천 한 번에 Redis 왕복이 그만큼 쌓인다.
     *
     * @return 값이 있는 구간만 담긴다. 없는 구간은 키 자체가 없다
     */
    Map<String, SegmentStatus> readAll(Collection<String> segmentIds);
}
