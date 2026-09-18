package com.ssafy.ddubi.domain.route.prediction;

import com.ssafy.ddubi.domain.route.mapping.BusCanonicalPath;
import java.time.Instant;
import java.util.*;

/** 승인된 모델 bundle의 학습 ID 대응·공통 전처리·추론을 감싸는 서버 내부 포트.
 * 이 입력은 최종 특성 벡터가 아니다. 어댑터가 학습 인코더/결측 규칙을 그대로 적용하고
 * 도로·날씨 등 가용시각 검증을 책임진다. 미설정·미지원 ID는 empty로 반환한다.
 * 네트워크 어댑터를 추가할 때 timeout/승인 모델 목록을 적용하고 원천 API 키를 이 계약에 넣지 않는다. */
public interface BusPredictionPort {
    Optional<Batch> predict(Input input);
    /** 검색 요청 하나의 호출 예산·캐시를 분리한다. 상태 없는 구현은 자기 자신을 반환한다. */
    default BusPredictionPort forRequest() { return this; }

    /** 외부 예외 원문 없이 계약에 정의한 실패 코드만 응답 경계로 전달한다. */
    class Unavailable extends RuntimeException {
        public Unavailable(String reason) { super(reason); }
    }

    /** 예측시각과 마스터·ID 정책을 고정한 한 차량의 전체 인접 경로. 시간 단위는 초다. */
    record Input(String inputVersion,String idPolicy,Instant predictionAt,BusCanonicalPath path,String digest) { }
    /** 평균 구간시간만 합산 가능하다. 분위수나 버스 도착 API 예측값은 허용하지 않는다. */
    record EdgeMean(String segmentId,int edgeSequence,double meanSeconds,long sampleCount) { }
    /**
     * 모델 수치와 승인 근거. PREVIEW 어댑터의 featureVersion/featuresAsOf/validUntil은 미제공 시 null이다.
     * 소비자는 이 경우 수치 PREVIEW만 공개하며 실제 시간 교체를 거절한다. 운영 승인에는 모든 증거가 필요하다.
     */
    record Batch(String modelVersion,String featureVersion,String inputDigest,String target,
            boolean productionReady,boolean trainingIdsVerified,Instant featuresAsOf,Instant computedAt,Instant validUntil,List<EdgeMean> edges) {
        public Batch {edges=List.copyOf(edges);}
    }
}
