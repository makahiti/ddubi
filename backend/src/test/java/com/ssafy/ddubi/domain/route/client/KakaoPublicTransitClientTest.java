package com.ssafy.ddubi.domain.route.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.RouteSearchQuery;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.service.KakaoRouteMapper;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoPublicTransitClientTest {

    private MockRestServiceServer server;
    private KakaoPublicTransitClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        KakaoPublicTransitProperties properties = new KakaoPublicTransitProperties();
        properties.setBaseUrl("https://dapi.kakao.com");
        properties.setRestApiKey("test-rest-api-key");
        client = new KakaoPublicTransitClient(
                builder,
                new ObjectMapper(),
                properties,
                new KakaoRouteMapper(
                        request -> TransitMappingResult.masterDataNotConfigured()
                )
        );
    }

    @Test
    void callsKakaoWithAuthorizationAndWgs84Coordinates() throws Exception {
        // 카카오로 나가는 URL, API 키 헤더, 좌표 이름이 공식 규격과 같은지 확인한다.
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/v2/routing/publictraffic");
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                            .isEqualTo("KakaoAK test-rest-api-key");
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("start_x")).isEqualTo("127.11119217");
                    assertThat(query.getFirst("start_y")).isEqualTo("37.39477123");
                    assertThat(query.getFirst("end_x")).isEqualTo("127.12628814");
                    assertThat(query.getFirst("end_y")).isEqualTo("37.41993056");
                    assertThat(query.getFirst("input_coord")).isEqualTo("WGS84");
                    assertThat(query.getFirst("output_coord")).isEqualTo("WGS84");
                })
                .andRespond(withSuccess(readFixture(), MediaType.APPLICATION_JSON));

        RouteSearchResult result = client.search(new RouteSearchQuery(
                new Coordinate(127.11119217, 37.39477123),
                new Coordinate(127.12628814, 37.41993056)
        ));

        assertThat(result.candidates()).hasSize(2);
        server.verify();
    }

    @Test
    void mapsKakaoQuotaErrorCodeMinusTenToRateLimited() {
        // 사용자가 실제로 받은 code=-10 응답이 우리 RATE_LIMITED 오류로 바뀌는지 확인한다.
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/v2/routing/publictraffic"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorType\":\"BadRequest\",\"code\":-10,"
                                + "\"message\":\"API limit has been exceeded.\"}"));

        assertThatThrownBy(() -> client.search(new RouteSearchQuery(
                new Coordinate(127.11119217, 37.39477123),
                new Coordinate(127.12628814, 37.41993056)
        ))).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));

        server.verify();
    }

    private String readFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/kakao-public-transit-response.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
