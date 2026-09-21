package com.ssafy.ddubi.domain.route.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 실제 네트워크 없이 공식 도보 요청/응답과 후보별 장애 격리를 검증한다. */
class KakaoWalkingRouteClientTest {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final KakaoWalkingProperties properties = new KakaoWalkingProperties();
    final KakaoPublicTransitProperties kakao = new KakaoPublicTransitProperties();
    final WalkingRouteClient.Request request =
            new WalkingRouteClient.Request(
                    new Coordinate(127.1, 37.5),
                    new Coordinate(127.2, 37.6),
                    "SHORTEST",
                    "walk-endpoint-v2");

    KakaoWalkingRouteClient client() {
        return new KakaoWalkingRouteClient(
                builder.baseUrl(properties.getBaseUrl()).build(),
                properties,
                kakao,
                new ObjectMapper(),
                new SimpleMeterRegistry());
    }

    void enabled() {
        properties.setEnabled(true);
        kakao.setRestApiKey("synthetic-test-key");
    }

    @Test
    void officialRequestUsesLongitudeLatitudeAndSeconds() {
        enabled();
        server.expect(
                        requestTo(
                                "https://dapi.kakao.com/v2/routing/walk?start_x=127.10000000&start_y=37.50000000&end_x=127.20000000&end_y=37.60000000&input_coord=WGS84&output_coord=WGS84&route_mode=SHORTEST"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "KakaoAK synthetic-test-key"))
                .andRespond(
                        withSuccess(
                                "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":3914,\"totalDistance\":4025},\"legs\":[]}}",
                                MediaType.APPLICATION_JSON));
        var result = client().route(request);
        assertThat(result.seconds()).isEqualTo(3914);
        assertThat(result.source()).isEqualTo("KAKAO_WALKING_API");
        assertThat(result.validUntil()).isAfter(Instant.now());
        server.verify();
    }

    @Test
    void disabledOrMissingKeyNeverCallsProvider() {
        assertThat(client().route(request).reason()).isEqualTo("WALK_DISABLED");
        properties.setEnabled(true);
        assertThat(client().route(request).reason()).isEqualTo("WALK_KEY_NOT_CONFIGURED");
        server.verify();
    }

    @Test
    void signalAllowanceIsAppliedToActualClientResponse() {
        enabled();
        server.expect(anything())
                .andRespond(
                        withSuccess(
                                """
                {"status":"OK","route":{"properties":{"totalTime":452,"totalDistance":600},
                "legs":[{"steps":[{"properties":{"distance":38,"guidance":"횡단보도 이용"}}]}]}}
                """,
                                MediaType.APPLICATION_JSON));
        assertThat(client().route(request).seconds()).isEqualTo(622);
        server.verify();
    }

    @Test
    void permissionFailureRemainsExplicitFallback() {
        enabled();
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN));
        var client = client();
        var result = client.route(request);
        assertThat(result.seconds()).isNull();
        assertThat(result.reason()).isEqualTo("WALK_HTTP_403");
        assertThat(client.health().getDetails()).containsEntry("walkingStatus", "WALK_HTTP_403");
        server.verify();
    }

    @Test
    void rateLimitHonorsRetryAfterAcrossCandidates() {
        enabled();
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));
        var client = client();
        assertThat(client.route(request).reason()).isEqualTo("WALK_HTTP_429");
        assertThat(client.route(request).reason()).isEqualTo("WALK_HTTP_429");
        server.verify();
    }

    @Test
    void timeoutDoesNotBecomeZeroWalkingTime() {
        enabled();
        server.expect(anything()).andRespond(withException(new java.net.SocketTimeoutException()));
        var result = client().route(request);
        assertThat(result.seconds()).isNull();
        assertThat(result.reason()).isEqualTo("WALK_TIMEOUT");
        server.verify();
    }

    @Test
    void serverFailureRemainsFallback() {
        enabled();
        server.expect(anything()).andRespond(withServerError());
        assertThat(client().route(request).reason()).isEqualTo("WALK_PROVIDER_UNAVAILABLE");
        server.verify();
    }

    @Test
    void emptyMalformedMissingAndInvalidResultsNeverApply() {
        enabled();
        for (String body :
                java.util.List.of(
                        "",
                        "not-json",
                        "{}",
                        "{\"status\":\"SAME_POINT\"}",
                        "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":-1,\"totalDistance\":20}}}",
                        "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":12.5,\"totalDistance\":20}}}",
                        "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":\"12\",\"totalDistance\":20}}}",
                        "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalDistance\":20}}}")) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            var result = client().route(request);
            assertThat(result.seconds()).isNull();
            assertThat(result.reason()).startsWith("WALK_");
            server.verify();
        }
    }

    @Test
    void walkingPathIsReturnedForMapDrawing() {
        enabled();
        server.expect(anything())
                .andRespond(
                        withSuccess(
                                """
                {"status":"OK","route":{"properties":{"totalTime":300,"totalDistance":400},
                "legs":[{"steps":[
                  {"properties":{"distance":93},"path":{"points":[[127.111197,37.394775],[127.111197,37.394748]]}},
                  {"properties":{"distance":40},"path":{"points":[[127.111197,37.394748],[127.111483,37.394745]]}}
                ]}]}}
                """,
                                MediaType.APPLICATION_JSON));
        var result = client().route(request);
        // step 경계에서 겹치는 같은 좌표는 한 번만 남는다.
        assertThat(result.path()).hasSize(3);
        assertThat(result.path().get(0)).isEqualTo(new Coordinate(127.111197, 37.394775));
        assertThat(result.path().get(2)).isEqualTo(new Coordinate(127.111483, 37.394745));
        server.verify();
    }

    @Test
    void brokenCoordinatesDropTheWholeLineButKeepTheTime() {
        enabled();
        for (String points :
                java.util.List.of(
                        "[[0,0],[127.1,37.5]]",
                        "[[200.0,37.5],[127.1,37.5]]",
                        "[[127.1]]",
                        "[[127.1,37.5]]")) {
            server.reset();
            server.expect(anything())
                    .andRespond(
                            withSuccess(
                                    "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":300,\"totalDistance\":400},"
                                            + "\"legs\":[{\"steps\":[{\"properties\":{\"distance\":93},\"path\":{\"points\":"
                                            + points
                                            + "}}]}]}}",
                                    MediaType.APPLICATION_JSON));
            var result = client().route(request);
            // 좌표가 이상해도 시간은 그대로 쓴다. 선만 그리지 않는다.
            assertThat(result.seconds()).isEqualTo(300);
            assertThat(result.path()).isEmpty();
            server.verify();
        }
    }

    @Test
    void responseWithoutPathStillSucceeds() {
        enabled();
        server.expect(anything())
                .andRespond(
                        withSuccess(
                                "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":300,\"totalDistance\":400},\"legs\":[{\"steps\":[{\"properties\":{\"distance\":93}}]}]}}",
                                MediaType.APPLICATION_JSON));
        var result = client().route(request);
        assertThat(result.seconds()).isEqualTo(300);
        assertThat(result.path()).isEmpty();
        server.verify();
    }

    /** 공식 문서의 응답 예시(2026-09-21 확인) 그대로 넣어 시간과 좌표를 함께 읽는지 본다. */
    @Test
    void officialSampleResponseIsReadAsTimeAndLine() {
        enabled();
        server.expect(anything())
                .andRespond(
                        withSuccess(
                                """
                {
                  "route": {
                    "properties": {
                      "totalDistance": 4025,
                      "totalTime": 3914,
                      "landingUrl": "https://map.kakao.com/link/by/walk/..."
                    },
                    "legs": [
                      {
                        "properties": {"distance": 4025, "time": 3914},
                        "steps": [
                          {
                            "properties": {
                              "distance": 93,
                              "guidance": "판교역 1번 출구까지 역사 내 이동",
                              "time": 84,
                              "x": 127.11119783,
                              "y": 37.39477573
                            },
                            "path": {
                              "points": [
                                [127.11119783, 37.39477573],
                                [127.11119779, 37.39474869],
                                [127.1114835, 37.39474572],
                                [127.11148211, 37.39533499]
                              ]
                            }
                          }
                        ]
                      }
                    ]
                  },
                  "status": "OK"
                }
                """,
                                MediaType.APPLICATION_JSON));
        var result = client().route(request);
        // 역사 내 이동 안내에는 횡단보도 여유를 더하지 않는다. 공식 totalTime 그대로다.
        assertThat(result.seconds()).isEqualTo(3914);
        assertThat(result.reason()).isNull();
        assertThat(result.path()).hasSize(4);
        assertThat(result.path().get(0)).isEqualTo(new Coordinate(127.11119783, 37.39477573));
        assertThat(result.path().get(3)).isEqualTo(new Coordinate(127.11148211, 37.39533499));
        server.verify();
    }
}
