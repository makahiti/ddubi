package com.ssafy.ddubi.domain.route.prediction;

import com.ssafy.ddubi.domain.recommendation.evaluation.BusPreviewClient;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 검색의 canonical BUS 경로를 v12 HTTP PREVIEW에 연결한다. 입력은 서울 공식 ID와 순번,
 * 시각은 검색 기준시각에서 앞 edge의 예측 초를 누적한 KST 시간대다. 일정 출발 평가는
 * JourneyEvaluationService가 별도로 수행한다. 학습 대응/운영 승인을 임의로 올리지 않는다.
 */
@Component
public class BusPreviewPredictionAdapter implements BusPredictionPort {
    private final BusPreviewClient client;
    public BusPreviewPredictionAdapter(BusPreviewClient client) { this.client=client; }
    public Optional<Batch> predict(Input input) { return forRequest().predict(input); }

    /** 한 검색에서 64개 고유 입력/5초의 새 호출 시작 예산을 공유한다. 실행 중 HTTP의 timeout은 별도다. */
    @Override public BusPredictionPort forRequest() {
        var cache=new HashMap<BusPreviewClient.Input,BusPreviewClient.Estimate>();
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        return input -> {
            if(!"SEOUL_BUS".equals(input.path().source().namespace())) throw new Unavailable("UNSUPPORTED_PROVIDER");
            var output=new ArrayList<EdgeMean>();
            Instant cursor=input.predictionAt(),computed=null; String version=null;
            for(var edge:input.path().edges()) {
                var time=cursor.atZone(ZoneId.of("Asia/Seoul"));
                var request=new BusPreviewClient.Input(edge.routeId(),edge.fromNodeId(),edge.toNodeId(),
                        edge.fromOrder(),time.toLocalDate(),time.getHour());
                var estimate=cache.get(request);
                if(estimate==null) {
                    if(cache.size()>=64 || System.nanoTime()>=deadline) throw new Unavailable("ML_BUDGET_EXHAUSTED");
                    estimate=client.predict(request); cache.put(request,estimate);
                }
                if(estimate.applicationFailure()!=null) throw new Unavailable(estimate.applicationFailure());
                if(estimate.modelVersion()==null || version!=null && !version.equals(estimate.modelVersion()))
                    throw new Unavailable("ML_MODEL_VERSION_MISMATCH");
                if(estimate.historySampleCount()==null || estimate.historySampleCount()<1)
                    throw new Unavailable("ML_HISTORY_INSUFFICIENT");
                version=estimate.modelVersion(); computed=estimate.computedAt();
                output.add(new EdgeMean(edge.segmentId(),edge.sequence(),estimate.seconds(),estimate.historySampleCount()));
                cursor=cursor.plusSeconds((long)Math.ceil(estimate.seconds()));
            }
            if(computed==null) throw new Unavailable("MAPPING_NOT_VERIFIED");
            // v12 HTTP는 특성 버전·원천 가용시각·예측 유효시각을 제공하지 않는다. 임의 값으로
            // 운영 포트 증거를 채우지 않고 null로 남긴다. 소비자는 수치 PREVIEW만 따로 공개한다.
            return Optional.of(new Batch(version,null,input.digest(),"ADJACENT_BUS_MEAN_SECONDS",false,false,
                    null,computed,null,output));
        };
    }
}
