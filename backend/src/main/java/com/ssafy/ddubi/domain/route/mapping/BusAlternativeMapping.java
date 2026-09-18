package com.ssafy.ddubi.domain.route.mapping;

import java.util.List;

/** 차량 대안 하나의 ID 확인과 공간 검증 결과. 후보의 공식 ID는 Redis/ML 키가 아니다.
 * 매핑 캐시의 predictionReady는 false다. 응답 복사본에서만 현재 모델 평가를 반영하며 다른 대안의 edge와 합치지 않는다. */
public record BusAlternativeMapping(String providerName, String identityStatus, String geometryStatus,
        String reason, boolean predictionReady, List<Candidate> candidates) {
    public BusAlternativeMapping { candidates=List.copyOf(candidates); }
    /** 출처·공식 순번을 포함한 부분경로와 거리 증거. 미측정 거리는 null이다. */
    public record Candidate(String namespace, String routeId, String routeName, String region,
            List<MetropolitanBusMaster.Stop> stops, String reason, String shapeStatus,
            Double startDistanceMeters, Double endDistanceMeters, boolean namesInferred) {
        public Candidate { stops=List.copyOf(stops); }
        public String identity() {
            return namespace+":"+routeId+":"+stops.stream().map(s->s.stopId()+"@"+s.sequence()).toList();
        }
    }
}
