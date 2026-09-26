package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Assessment;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Result;
import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSegment;
import com.ssafy.ddubi.domain.route.model.RouteType;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** 가짜 TOPIS 상태가 서울 버스 일정 한 건에만 붙고, 대안이 없으면 처음부터 선택하지 않는지 확인한다. */
class RoadClosureDemoSupportTest {
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void choosesBusOnlyWhenDistinctUsableAlternativeExists() {
        var bus = candidate(0, TransportMode.BUS, "BUS:100:1:2");
        var subway = candidate(1, TransportMode.SUBWAY, "SUB:2:3:4");
        var busResult = result(bus, 800);
        var alternative = result(subway, 900);

        assertThat(
                        RoadClosureDemoSupport.initialBusChoice(
                                List.of(bus, subway), List.of(busResult, alternative)))
                .contains(busResult);
        assertThat(
                        RoadClosureDemoSupport.initialBusChoice(
                                List.of(bus, subway), List.of(busResult)))
                .isEmpty();
        // 처음부터 지하철이 더 빠르면, 버스를 강제로 고른 순간 이미 대안이 뜬다.
        // 시연 사고가 경로 변경의 원인이라는 인과를 지키기 위해 이 경우는 건너뛴다.
        assertThat(
                        RoadClosureDemoSupport.initialBusChoice(
                                List.of(bus, subway),
                                List.of(result(bus, 900), result(subway, 800))))
                .isEmpty();
    }

    @Test
    void neverTreatsMetropolitanBusAsSeoulDemoEdge() {
        var bus = candidate(0, TransportMode.BUS, "BUS:GBIS.100:GBIS.1:GBIS.2");
        var subway = candidate(1, TransportMode.SUBWAY, "SUB:2:3:4");
        assertThat(
                        RoadClosureDemoSupport.initialBusChoice(
                                List.of(bus, subway),
                                List.of(result(bus, 900), result(subway, 800))))
                .isEmpty();
    }

    @Test
    void overlaysOnlyTheMatchingScheduleWithoutWritingSharedState() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String payload =
                "{\"source\":\"DEMO_TOPIS\",\"demo\":true,\"scheduleId\":7,"
                        + "\"inputRevision\":3,"
                        + "\"segmentId\":\"BUS:100:1:2\",\"eventId\":\"DEMO-TOPIS-7\","
                        + "\"status\":\"blocked\",\"computedAt\":\"2026-09-24T00:00:00Z\","
                        + "\"validUntil\":\"2026-09-24T01:00:00Z\"}";
        when(values.get("demo:topis:road-control:schedule:7")).thenReturn(payload);
        var support =
                new RoadClosureDemoSupport(
                        true, mock(JdbcTemplate.class), redis, new ObjectMapper());
        var original = new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());
        var routes = List.of(candidate(0, TransportMode.BUS, "BUS:100:1:2"));

        var affected = support.overlay(7, 3, original, routes, now);
        var other = support.overlay(8, 3, original, routes, now);
        var edited = support.overlay(7, 4, original, routes, now);

        assertThat(affected.states().get("BUS:100:1:2").path("status").asText())
                .isEqualTo("blocked");
        assertThat(affected.reasons()).contains("DEMO_TOPIS_ROAD_CONTROL");
        assertThat(other).isEqualTo(original);
        assertThat(edited).isEqualTo(original);
    }

    /**
     * 화면·알림 문장이 실제 돌발 공지처럼 읽히도록 막힌 구간의 정류장과 버스 번호를 쓴다. "가짜" 같은 말은 넣지 않고, 시연임은 앞의 "[시연]" 으로만
     * 밝힌다(2026-09-25 요청).
     */
    @Test
    void describesTheBlockedHopLikeARealNotice() {
        var route = candidate(0, TransportMode.BUS, "BUS:100:1:2");

        var place = RoadClosureDemoSupport.place(route, "BUS:100:1:2");

        assertThat(place.routeName()).isEqualTo("노선");
        assertThat(place.message()).isEqualTo("A → B 구간 도로 통제로 노선번 버스 운행에 차질이 있어요.");
        // 정류장 이름 뒤의 보조 설명(".갈월동")은 빼고, 경로에 없는 구간이면 아는 만큼만 쓴다.
        var dotted = candidate(0, TransportMode.BUS, "BUS:100:1:2", List.of("숙대입구역.갈월동", "숙명여대"));
        assertThat(RoadClosureDemoSupport.place(dotted, "BUS:100:1:2").fromStop())
                .isEqualTo("숙대입구역");
        assertThat(RoadClosureDemoSupport.place(route, "BUS:999:8:9").message())
                .isEqualTo("버스 경로 구간 도로 통제로 버스 운행에 차질이 있어요.");
    }

    @Test
    void displayMessageAlwaysStartsWithDemoLabelAndNeverSaysFake() throws Exception {
        var json = new ObjectMapper();
        assertThat(
                        RoadClosureDemoSupport.displayMessage(
                                json.readTree(
                                        "{\"message\":\"A → B 구간 도로 통제로 262번 버스 운행에 차질이 있어요.\"}")))
                .isEqualTo("[시연] A → B 구간 도로 통제로 262번 버스 운행에 차질이 있어요.");
        // 문장이 없던 예전 키
        assertThat(RoadClosureDemoSupport.displayMessage(json.readTree("{}")))
                .isEqualTo("[시연] 지금 버스 경로에 도로 통제가 있어요.")
                .doesNotContain("가짜");
    }

    /**
     * 도로 통제는 두 정류장 사이 도로를 막는다. 같은 도로를 번호만 다른 버스로 가는 경로는 대안이 아니다(2026-09-26 제보: 145번을 막았더니 3030번으로
     * 같은 길을 가는 경로가 "새 경로" 로 나왔다).
     */
    @Test
    void sameRoadOnAnotherBusIsNotAnAlternative() {
        var bus = candidate(0, TransportMode.BUS, "BUS:145:1:2");
        var sameRoad = candidate(1, TransportMode.BUS, "BUS:3030:1:2");
        var subway = candidate(2, TransportMode.SUBWAY, "SUB:2:3:4");

        assertThat(RoadClosureDemoSupport.stopPair("BUS:145:1:2")).isEqualTo("1:2");
        assertThat(RoadClosureDemoSupport.stopPair("SUB:2:3:4")).isNull();
        assertThat(
                        RoadClosureDemoSupport.initialBusChoice(
                                List.of(bus, sameRoad),
                                List.of(result(bus, 800), result(sameRoad, 850))))
                .isEmpty();
        assertThat(
                        RoadClosureDemoSupport.initialBusChoice(
                                List.of(bus, sameRoad, subway),
                                List.of(
                                        result(bus, 800),
                                        result(sameRoad, 850),
                                        result(subway, 900))))
                .contains(result(bus, 800));
    }

    @Test
    void overlayBlocksEveryBusOnTheSameRoad() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:topis:road-control:schedule:7"))
                .thenReturn(
                        "{\"source\":\"DEMO_TOPIS\",\"demo\":true,\"scheduleId\":7,\"inputRevision\":3,"
                                + "\"segmentId\":\"BUS:145:1:2\",\"stopPair\":\"1:2\",\"eventId\":\"DEMO-TOPIS-7-3\","
                                + "\"status\":\"blocked\",\"computedAt\":\"2026-09-24T00:00:00Z\","
                                + "\"validUntil\":\"2026-09-24T01:00:00Z\"}");
        var support =
                new RoadClosureDemoSupport(
                        true, mock(JdbcTemplate.class), redis, new ObjectMapper());
        var routes =
                List.of(
                        candidate(0, TransportMode.BUS, "BUS:145:1:2"),
                        candidate(1, TransportMode.BUS, "BUS:3030:1:2"),
                        candidate(2, TransportMode.BUS, "BUS:147:5:6"));

        var affected =
                support.overlay(
                        7,
                        3,
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        routes,
                        now);

        assertThat(affected.states()).containsKeys("BUS:145:1:2", "BUS:3030:1:2");
        assertThat(affected.states()).doesNotContainKey("BUS:147:5:6");
    }

    @Test
    void messageSaysOtherBusesTooWhenTheRoadIsShared() {
        var route = candidate(0, TransportMode.BUS, "BUS:145:1:2");
        var other = candidate(1, TransportMode.BUS, "BUS:3030:1:2");

        assertThat(
                        RoadClosureDemoSupport.place(route, "BUS:145:1:2", List.of(route, other))
                                .message())
                .isEqualTo("A → B 구간 도로 통제로 노선번 등 버스 운행에 차질이 있어요.");
        assertThat(RoadClosureDemoSupport.place(route, "BUS:145:1:2", List.of(route)).message())
                .isEqualTo("A → B 구간 도로 통제로 노선번 버스 운행에 차질이 있어요.");
    }

    private RouteCandidate candidate(int index, TransportMode mode, String id) {
        return candidate(index, mode, id, List.of("A", "B"));
    }

    private RouteCandidate candidate(int index, TransportMode mode, String id, List<String> stops) {
        var edge =
                new MappedTransitSegment(
                        1, id, mode, "100", null, "노선", "FORWARD", "1", "2", null, null, 1, 2);
        var mapping =
                new TransitMappingResult(
                        TransitMappingStatus.EXACT,
                        "v1",
                        "policy",
                        "100",
                        "노선",
                        "1",
                        "2",
                        1,
                        2,
                        "FORWARD",
                        1,
                        1,
                        1,
                        null,
                        null,
                        List.of(edge));
        var segment =
                new RouteSegment(
                        1, mode, id, "노선", null, "A", "B", 2000, 900, stops, List.of(), List.of(),
                        mapping);
        return new RouteCandidate(
                index,
                mode == TransportMode.BUS ? RouteType.BUS : RouteType.SUBWAY,
                2000,
                900,
                0,
                null,
                0,
                0,
                List.of(segment));
    }

    private Result result(RouteCandidate route, long arrivalSeconds) {
        return new Result(
                RouteEvidenceAssembler.key(route),
                now,
                now.plusSeconds(arrivalSeconds),
                Assessment.ON_TIME_ESTIMATE,
                false,
                List.of(),
                List.of(),
                "test");
    }
}
