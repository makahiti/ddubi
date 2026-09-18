package com.ssafy.ddubi.domain.route.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** BUS provider step의 차량/출처 대안을 독립 평가하는 응용 경계.
 * 모든 인접 edge의 유효 평균을 합산하고 승인된 결과만 실제 예상시간으로 반환한다.
 * 미승인 수치는 별도 PREVIEW 필드에만 제공하며, 한 edge라도 빠지면 수치도 제공하지 않는다.
 * 다른 대안·대기·환승시간을 더하지 않고 step 전체 API fallback을 유지한다. DB 쓰기는 없다. */
@Service
public class BusJourneyEvaluator {
    private final BusPredictionPort predictor;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();

    /** 모델 어댑터가 미등록이면 실제 예측을 꾸미지 않고 모든 요청에 명시적 fallback을 제공한다. */
    @Autowired
    public BusJourneyEvaluator(ObjectProvider<BusPredictionPort> providers) {
        this(providers.getIfAvailable(()->input->Optional.empty()));
    }
    /** 테스트 또는 승인된 배포 어댑터를 명시적으로 주입한다. 외부 시계는 평가 인수로 고정한다. */
    public BusJourneyEvaluator(BusPredictionPort predictor) {this.predictor=Objects.requireNonNull(predictor);}
    /** DTO 전체 후보가 같은 요청 예산을 공유하도록 요청 시작에 한 번 호출한다. */
    public BusJourneyEvaluator forRequest() { return new BusJourneyEvaluator(predictor.forRequest()); }

    /** 선택자가 참조할 대안/출처 순번과 계산 출처다. estimatedSeconds는 전체 버스 step 기준이며 단위는 초다. */
    public record Evaluation(int alternativeIndex,int candidateIndex,String providerName,boolean predictionReady,
            String source,String reason,double estimatedSeconds,String modelVersion,String featureVersion,
            String inputDigest,List<String> segmentIds,Double previewEstimatedSeconds) {
        public Evaluation {segmentIds=List.copyOf(segmentIds);}
        public Evaluation(int alternativeIndex,int candidateIndex,String providerName,boolean predictionReady,
                String source,String reason,double estimatedSeconds,String modelVersion,String featureVersion,
                String inputDigest,List<String> segmentIds) {
            this(alternativeIndex,candidateIndex,providerName,predictionReady,source,reason,estimatedSeconds,
                    modelVersion,featureVersion,inputDigest,segmentIds,null);
        }
    }

    /** 응답 DTO 작성 시 호출한다. 원본 카카오 시간과 전체 후보 순위는 수정하지 않는다. */
    public List<Evaluation> evaluate(RouteSegment segment,Instant at) {
        if(segment.mode()!=TransportMode.BUS)return List.of();
        var result=new ArrayList<Evaluation>();var alternatives=segment.mapping().busAlternatives();
        for(int a=0;a<alternatives.size();a++) {
            var alt=alternatives.get(a);
            if(!"VERIFIED".equals(alt.geometryStatus()) || alt.candidates().isEmpty()) {
                result.add(fallback(a,-1,alt.providerName(),segment.durationSeconds(),"MAPPING_NOT_VERIFIED",null,List.of()));continue;
            }
            for(int c=0;c<alt.candidates().size();c++) {
                try {
                    var path=BusCanonicalPath.select(segment.mapping(),a,c);
                    result.add(evaluatePath(path,a,c,segment.durationSeconds(),at));
                } catch(IllegalArgumentException e) {
                    result.add(fallback(a,c,alt.providerName(),segment.durationSeconds(),"CANONICAL_PATH_INVALID",null,List.of()));
                }
            }
        }
        if(result.isEmpty())result.add(fallback(-1,-1,segment.providerRouteKey(),segment.durationSeconds(),"MASTER_OR_MAPPING_UNAVAILABLE",null,List.of()));
        return List.copyOf(result);
    }

    /** 마스터·전체 source 좌표/순번·예측시각을 지문화해 다른 요청의 모델 결과가 재사용되지 않게 한다. */
    public BusPredictionPort.Input input(BusCanonicalPath path,Instant at) {
        Objects.requireNonNull(at);
        try {
            var content=List.of("bus-input-v1",CanonicalSegmentIdFactory.BUS_POLICY,at.toString(),path);
            return new BusPredictionPort.Input("bus-input-v1",CanonicalSegmentIdFactory.BUS_POLICY,at,path,
                    MetropolitanBusMaster.sha(JSON.writeValueAsBytes(content)));
        } catch(java.io.IOException e){throw new IllegalArgumentException("예측 입력 직렬화 실패",e);}
    }

    private Evaluation evaluatePath(BusCanonicalPath path,int a,int c,long fallback,Instant at) {
        var input=input(path,at);var ids=path.edges().stream().map(MappedTransitSegment::segmentId).toList();
        try {
            var response=predictor.predict(input);
            if(response.isEmpty())return fallback(a,c,path.providerName(),fallback,"MODEL_OR_TRAINING_CROSSWALK_UNAVAILABLE",input.digest(),ids);
            var batch=response.get();
            if(batch.modelVersion()==null || batch.modelVersion().isBlank()
                    || !input.digest().equals(batch.inputDigest()) || !"ADJACENT_BUS_MEAN_SECONDS".equals(batch.target())
                    || batch.computedAt()==null
                    || batch.featuresAsOf()!=null && batch.featuresAsOf().isAfter(at)
                    || batch.validUntil()!=null && (!batch.validUntil().isAfter(at) || !batch.validUntil().isAfter(batch.computedAt()))
                    || batch.edges().size()!=ids.size())
                return fallback(a,c,path.providerName(),fallback,"MODEL_RESULT_CONTRACT_MISMATCH",input.digest(),ids);
            // 운영 승인에는 출처 시각/특성 버전/유효시각이 모두 필요하다. 미제공 v12 PREVIEW는
            // 이를 가짜로 채우지 않고 아래 별도 필드로만 공개하며 운영 결과로 승격하지 않는다.
            if(batch.productionReady() && batch.trainingIdsVerified()
                    && (batch.featureVersion()==null || batch.featureVersion().isBlank()
                        || batch.featuresAsOf()==null || batch.validUntil()==null))
                return fallback(a,c,path.providerName(),fallback,"MODEL_RESULT_CONTRACT_MISMATCH",input.digest(),ids);
            // 동일 경로가 같은 물리 edge를 재방문할 수 있으므로 ID만이 아니라 경로 내 순번까지 대조한다.
            double seconds=0;
            for(int i=0;i<ids.size();i++) {
                var edge=batch.edges().get(i);
                if(!ids.get(i).equals(edge.segmentId()) || edge.edgeSequence()!=i+1 || !Double.isFinite(edge.meanSeconds())
                        || edge.meanSeconds()<=0 || edge.sampleCount()<1)
                    return fallback(a,c,path.providerName(),fallback,"INCOMPLETE_OR_INVALID_EDGE_PREDICTION",input.digest(),ids);
                seconds+=edge.meanSeconds();
            }
            if(!Double.isFinite(seconds))return fallback(a,c,path.providerName(),fallback,"PREDICTION_OVERFLOW",input.digest(),ids);
            // 검증용 수치는 별도 필드로 공개한다. 기존 estimatedSeconds/총시간/운영 승인 의미는 유지한다.
            if(!batch.productionReady() || !batch.trainingIdsVerified())
                return new Evaluation(a,c,path.providerName(),false,"KAKAO","MODEL_NOT_APPROVED",fallback,
                        batch.modelVersion(),batch.featureVersion(),input.digest(),ids,seconds);
            return new Evaluation(a,c,path.providerName(),true,"ML_ADJACENT_MEAN_SUM","NONE",seconds,batch.modelVersion(),batch.featureVersion(),input.digest(),ids);
        } catch(BusPredictionPort.Unavailable e) {
            // 어댑터가 생성한 제한된 코드만 전달한다. 네트워크 예외/응답 원문은 아래 일반 실패로 격리한다.
            String reason=Set.of("UNSUPPORTED_PROVIDER","REFERENCE_DATE_UNSUPPORTED","UNSEEN_PROVIDER_ID",
                    "ML_HISTORY_INSUFFICIENT","ML_BUDGET_EXHAUSTED","ML_MODEL_VERSION_MISMATCH","ML_NOT_CONFIGURED",
                    "ML_HTTP_UNAVAILABLE","ML_TIMEOUT","ML_UNAVAILABLE","ML_INVALID_RESPONSE","ML_INVALID_DURATION",
                    "ML_INTERRUPTED","MAPPING_NOT_VERIFIED").contains(e.getMessage())?e.getMessage():"MODEL_CALL_FAILED";
            return fallback(a,c,path.providerName(),fallback,reason,input.digest(),ids);
        } catch(RuntimeException e) {
            // 어댑터 예외 원문에는 서버 URL/비밀값이 포함될 수 있어 응답에 복제하지 않는다.
            return fallback(a,c,path.providerName(),fallback,"MODEL_CALL_FAILED",input.digest(),ids);
        }
    }
    private Evaluation fallback(int a,int c,String name,long seconds,String reason,String digest,List<String> ids) {
        return new Evaluation(a,c,name,false,"KAKAO",reason,seconds,null,null,digest,ids);
    }
}
