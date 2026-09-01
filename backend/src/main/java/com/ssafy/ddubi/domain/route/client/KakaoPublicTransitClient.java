package com.ssafy.ddubi.domain.route.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse;
import com.ssafy.ddubi.domain.route.model.RouteSearchQuery;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.service.KakaoRouteMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Locale;

/** 카카오맵 서버에 대중교통 경로를 요청하고 응답을 내부 모델로 변환하는 외부 연동 클래스. */
@Component
public class KakaoPublicTransitClient implements PublicTransitRouteClient {

    // RestClient는 실제 HTTP 요청, ObjectMapper는 카카오 오류 JSON 분석에 사용한다.
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KakaoPublicTransitProperties properties;
    private final KakaoRouteMapper routeMapper;

    public KakaoPublicTransitClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            KakaoPublicTransitProperties properties,
            KakaoRouteMapper routeMapper
    ) {
        // 공통 주소(https://dapi.kakao.com)를 한 번 설정해 매 요청마다 반복하지 않는다.
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.routeMapper = routeMapper;
    }

    @Override
    public RouteSearchResult search(RouteSearchQuery query) {
        // 키 없이 요청하면 카카오에서 401/403이 발생한다. 호출 전에 설정 문제를 명확히 알려준다.
        if (!StringUtils.hasText(properties.getRestApiKey())) {
            throw new BusinessException(
                    ErrorCode.ROUTE_PROVIDER_UNAVAILABLE,
                    "KAKAO_REST_API_KEY가 설정되지 않았습니다."
            );
        }

        try {
            // 카카오 공식 규격: GET 요청 + KakaoAK 인증 헤더 + WGS84 좌표 쿼리 파라미터.
            KakaoPublicTransitResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(properties.getPublicTransitPath())
                            .queryParam("start_x", format(query.start().longitude()))
                            .queryParam("start_y", format(query.start().latitude()))
                            .queryParam("end_x", format(query.end().longitude()))
                            .queryParam("end_y", format(query.end().latitude()))
                            .queryParam("input_coord", "WGS84")
                            .queryParam("output_coord", "WGS84")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.getRestApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(KakaoPublicTransitResponse.class);

            // 카카오 전용 JSON을 프론트에 그대로 주지 않고 우리 Route 모델로 변환한다.
            return routeMapper.map(response, Instant.now());
        } catch (BusinessException exception) {
            // Mapper가 판단한 정상적인 업무 오류(경로 없음 등)는 의미를 유지한다.
            throw exception;
        } catch (HttpStatusCodeException exception) {
            // 카카오는 쿼터 초과를 HTTP 429 또는 code=-10 형태로 줄 수 있다.
            if (exception.getStatusCode().value() == 429 || isQuotaExceeded(exception.getResponseBodyAsString())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }
            throw new BusinessException(ErrorCode.ROUTE_PROVIDER_UNAVAILABLE);
        } catch (RestClientException exception) {
            // 연결 실패, 타임아웃, 읽을 수 없는 응답 등은 외부 서비스 장애로 통일한다.
            throw new BusinessException(ErrorCode.ROUTE_PROVIDER_UNAVAILABLE);
        }
    }

    /** 카카오 오류 본문에서 일일/월간 호출 한도 초과(code=-10)를 찾는다. */
    private boolean isQuotaExceeded(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("code").asInt(Integer.MIN_VALUE) == -10
                    || root.path("message").asText("").contains("API limit has been exceeded")
                    || root.path("msg").asText("").contains("API limit has been exceeded");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 운영체제 언어 설정과 상관없이 좌표의 소수점 구분자를 항상 점(.)으로 만든다. */
    private String format(double value) {
        return String.format(Locale.ROOT, "%.8f", value);
    }
}
