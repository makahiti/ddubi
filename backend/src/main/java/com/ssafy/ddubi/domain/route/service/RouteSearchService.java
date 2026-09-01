package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.dto.RouteSearchRequest;
import com.ssafy.ddubi.domain.route.dto.RouteSearchResponse;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.RouteSearchQuery;
import org.springframework.stereotype.Service;

/** Controller의 입력을 내부 모델로 바꾸고 길찾기 Client 호출을 조정하는 응용 서비스. */
@Service
public class RouteSearchService {

    // 구현체는 현재 KakaoPublicTransitClient지만, 인터페이스를 사용해 다른 업체로 교체할 수 있다.
    private final PublicTransitRouteClient routeClient;

    public RouteSearchService(PublicTransitRouteClient routeClient) {
        this.routeClient = routeClient;
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
        return RouteSearchResponse.from(routeClient.search(query));
    }
}
