package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.dto.RouteSearchRequest;
import com.ssafy.ddubi.domain.route.dto.RouteSearchResponse;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.RouteSearchQuery;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.ssafy.ddubi.domain.route.prediction.BusJourneyEvaluator;

/** Controller의 입력을 내부 모델로 바꾸고 길찾기 Client 호출을 조정하는 응용 서비스. */
@Service
public class RouteSearchService {

    // 구현체는 현재 KakaoPublicTransitClient지만, 인터페이스를 사용해 다른 업체로 교체할 수 있다.
    private final PublicTransitRouteClient routeClient;

    private final BusJourneyEvaluator evaluator;
    @Autowired
    public RouteSearchService(PublicTransitRouteClient routeClient,BusJourneyEvaluator evaluator) {
        this.routeClient=routeClient;this.evaluator=evaluator;
    }
    /** 기존 단위 테스트는 외부 모델 없이 명시적 fallback으로 실행한다. */
    public RouteSearchService(PublicTransitRouteClient routeClient) {
        this(routeClient,new BusJourneyEvaluator(input->java.util.Optional.empty()));
    }

    /**
     * API 요청 DTO를 내부 좌표 모델로 바꾸고 외부 길찾기 클라이언트에 전달한다.
     * 이 메서드는 DB에 저장하지 않는 '조회 전용' 흐름이다.
     */
    public RouteSearchResponse search(RouteSearchRequest request) {
        // 프론트 DTO를 카카오와 무관한 내부 검색 조건으로 변환한다.
        RouteSearchQuery query = new RouteSearchQuery(
                new Coordinate(request.startLongitude(), request.startLatitude()),
                new Coordinate(request.endLongitude(), request.endLatitude())
        );
        // 외부 응답을 그대로 노출하지 않고 우리 서비스 응답 DTO로 한 번 더 감싼다.
        return RouteSearchResponse.from(routeClient.search(query),evaluator);
    }

    /**
     * 추천 계산용 내부 조회. 공개 응답 DTO 는 매핑 결과(canonical 인접구간)를 빼고 나가는데,
     * 추천은 그 값이 있어야 기준선·실시간 상태를 붙일 수 있다. 그래서 내부 모델을 그대로 준다.
     *
     * <p>클라이언트를 추천 도메인에 직접 노출하지 않으려고 여기에 둔다.
     */
    public RouteSearchResult searchInternal(Coordinate start, Coordinate end) {
        return routeClient.search(new RouteSearchQuery(start, end));
    }
}
