package com.ssafy.ddubi.domain.recommendation.adapter;

import com.ssafy.ddubi.domain.recommendation.port.BusSegmentPredictionRequest;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePrediction;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePredictor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예측 서비스가 없을 때 쓰는 기본 구현. <b>모든 구간을 이유와 함께 UNAVAILABLE 로 돌려준다.</b>
 *
 * <h3>왜 빈 Map 이 아니라 이유를 채우나</h3>
 * 폴백은 조용하면 안 된다. 추천 응답에 출처가 남아야 나중에 "이 추천이 왜 이렇게 나왔나" 를
 * 설명할 수 있는데, 빈 Map 을 주면 호출한 쪽은 <b>예측이 없었던 건지 서비스가 죽은 건지</b>
 * 구분하지 못한다. 둘은 대응이 다르다.
 */
public class UnavailableBusTravelTimePredictor implements BusTravelTimePredictor {

    public static final String REASON = "예측 서비스가 연결되지 않았습니다.";

    @Override
    public Map<String, BusTravelTimePrediction> predict(List<BusSegmentPredictionRequest> requests) {
        Map<String, BusTravelTimePrediction> results = new LinkedHashMap<>();
        for (BusSegmentPredictionRequest request : requests) {
            results.put(request.segmentId(), BusTravelTimePrediction.unavailable(REASON));
        }
        return results;
    }
}
