package com.ssafy.ddubi.domain.route.client;

import com.ssafy.ddubi.domain.route.model.RouteSearchQuery;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;

/**
 * 대중교통 길찾기 업체와 우리 서비스 사이의 약속이다.
 * Service는 카카오 클래스에 직접 의존하지 않으므로 나중에 다른 업체를 붙여도 Service를 바꿀 필요가 없다.
 */
public interface PublicTransitRouteClient {

    /** 출발지와 도착지로 경로를 검색하고 우리 내부 모델로 변환한 결과를 반환한다. */
    RouteSearchResult search(RouteSearchQuery query);
}
