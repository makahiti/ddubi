/**
 * 카카오맵 대중교통 길찾기를 담당하는 패키지다.
 *
 * <p>처리 순서는 다음과 같다.</p>
 * <pre>
 * 프론트 요청
 *   → controller.RouteSearchController
 *   → service.RouteSearchService
 *   → client.KakaoPublicTransitClient
 *   → 카카오 API
 *   → service.KakaoRouteMapper
 *   → model.RouteCandidate / model.RouteSegment
 *   → 프론트 응답
 * </pre>
 *
 * <p>현재 검색 API는 결과를 DB에 자동 저장하지 않는다. 사용자가 경로를 한 번 검색했다는 이유만으로
 * 데이터가 계속 쌓이지 않게 하고, 추후 일정 생성·재계산 흐름에서 필요할 때만 저장하기 위해서다.</p>
 */
package com.ssafy.ddubi.domain.route;
