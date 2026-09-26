package com.ssafy.ddubi.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegmentEdge;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MovementConditionsServiceTest {

    @Test
    void 서울시청좌표를Spark와같은기상청격자로변환한다() {
        var grid = MovementConditionsService.kmaGrid(37.5665, 126.9780);

        assertThat(grid.nx()).isEqualTo(60);
        assertThat(grid.ny()).isEqualTo(127);
    }

    @Test
    void 유효한날씨만표시하고선택경로가없으면다른변수는확인불가로둔다() {
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        MovementConditionsRedisReader redis = mock(MovementConditionsRedisReader.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "서울시청",
                                new BigDecimal("37.5665000"),
                                new BigDecimal("126.9780000")));
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.empty());
        when(redis.value("weather:latest:60:127"))
                .thenReturn(
                        """
{
  "districts":["종로구","중구"],
  "features":{"temperatureC":20.9,"precipitationMm":0.0,"precipitationTypeCode":0},
  "observedAt":"2026-09-22T01:00:00+09:00",
  "validUntil":"2026-09-22T03:00:00+09:00",
  "source":"kma.ultra-srt-ncst"
}
""");
        var service =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 12);

        var result = service.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));

        assertThat(result.weather().available()).isTrue();
        assertThat(result.weather().temperatureC()).isEqualTo(20.9);
        assertThat(result.congestion().available()).isFalse();
        assertThat(result.congestion().reason()).isEqualTo("NO_BUS_SEGMENT");
        assertThat(result.roadBlock().available()).isFalse();
        assertThat(result.subwayDelay().available()).isFalse();
        assertThat(result.observedAt()).isEqualTo(Instant.parse("2026-09-21T16:00:00Z"));
    }

    @Test
    void 만료된날씨를맑음이나강수량영으로표시하지않는다() {
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        MovementConditionsRedisReader redis = mock(MovementConditionsRedisReader.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "서울시청",
                                new BigDecimal("37.5665000"),
                                new BigDecimal("126.9780000")));
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.empty());
        when(redis.value("weather:latest:60:127"))
                .thenReturn(
                        """
                        {"features":{"precipitationMm":0.0},
                         "observedAt":"2026-09-22T01:00:00+09:00",
                         "validUntil":"2026-09-22T01:30:00+09:00"}
                        """);
        var service =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 12);

        var result = service.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));

        assertThat(result.weather().available()).isFalse();
        assertThat(result.weather().reason()).isEqualTo("WEATHER_STALE");
        assertThat(result.weather().precipitationMm()).isNull();
    }

    /**
     * 도시데이터는 6시간마다 모으는데 관측 유효시간은 15분이라, 유효시각만 보면 하루 대부분 혼잡도가 비어 있다. 상한 안이면 값을 주되 stale 로 표시해 앱이 "○시
     * 기준" 을 붙인다.
     */
    @Test
    void 유효시각이지난혼잡도는상한안이면기준시각과함께준다() {
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        MovementConditionsRedisReader redis = mock(MovementConditionsRedisReader.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "서울시청",
                                new BigDecimal("37.5665000"),
                                new BigDecimal("126.9780000")));
        SavedRoute route = SavedRoute.builder().baseDurationMin(30).selected(true).build();
        SavedRouteSegment bus =
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.BUS)
                        .fromName("A")
                        .toName("B")
                        .baseDurationMin(10)
                        .build();
        bus.addEdge(
                SavedRouteSegmentEdge.builder()
                        .edgeSeq(1)
                        .segmentId("BUS:100:A:B")
                        .mappingVersion("master-v1")
                        .policyVersion("policy-v1")
                        .build());
        route.addSegment(bus);
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.of(route));
        when(redis.value("ml:mapping:bus-city:active:master-v1"))
                .thenReturn("ml:mapping:bus-city:v1:master-v1:sha");
        when(redis.hashValue("ml:mapping:bus-city:v1:master-v1:sha", "BUS:100:A:B"))
                .thenReturn(
                        """
                        {"masterVersion":"master-v1","mappingStatus":"RESOLVED",
                         "primaryProviderAreaId":"POI001"}
                        """);
        // 관측 6시간 전, 유효시각은 5시간 45분 전에 이미 지났다.
        when(redis.value("city:latest:area:POI001"))
                .thenReturn(
                        """
                        {"areaName":"시청","processingStatus":"VALID","congestionLevel":"BUSY",
                         "congestionScore":3,"observedAt":"2026-09-21T11:00:00Z",
                         "validUntil":"2026-09-21T11:15:00Z"}
                        """);
        var service =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 12);

        var result = service.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));

        assertThat(result.congestion().available()).isTrue();
        assertThat(result.congestion().stale()).isTrue();
        assertThat(result.congestion().level()).isEqualTo("BUSY");
        assertThat(result.congestion().observedAt())
                .isEqualTo(Instant.parse("2026-09-21T11:00:00Z"));

        // 상한(1시간)을 넘기면 쓰지 않는다.
        var strict =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 1);
        var refused = strict.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));
        assertThat(refused.congestion().available()).isFalse();
        assertThat(refused.congestion().reason()).isEqualTo("CONGESTION_UNAVAILABLE_OR_STALE");
    }

    /**
     * 발표용 통제가 걸리면 도로 칸에도 보여준다. 문장은 실제 돌발 공지 모양에 "[시연]" 만 붙인다(2026-09-25). 추천은 그 통제로 경로를 바꾸는데 도로 칸만
     * "확인된 통제 없음" 이면 화면이 어긋난다. 기능이 꺼져 있거나, 일정을 고쳐 입력 버전이 바뀌었거나, 지금 경로의 버스 구간이 아니면 보여주지 않는다.
     */
    @Test
    void 시연통제는기능이켜지고같은일정같은입력버전의버스구간일때만시연으로표시한다() {
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        MovementConditionsRedisReader redis = mock(MovementConditionsRedisReader.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.getId()).thenReturn(3L);
        when(schedule.getInputRevision()).thenReturn(2L);
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "서울시청",
                                new BigDecimal("37.5665000"),
                                new BigDecimal("126.9780000")));
        SavedRoute route = SavedRoute.builder().baseDurationMin(30).selected(true).build();
        SavedRouteSegment bus =
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.BUS)
                        .fromName("A")
                        .toName("B")
                        .baseDurationMin(10)
                        .build();
        bus.addEdge(
                SavedRouteSegmentEdge.builder()
                        .edgeSeq(1)
                        .segmentId("BUS:100:A:B")
                        .mappingVersion("master-v1")
                        .policyVersion("policy-v1")
                        .build());
        route.addSegment(bus);
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.of(route));
        String event =
                """
                {"source":"DEMO_TOPIS","demo":true,"scheduleId":3,"inputRevision":%d,
                 "eventId":"DEMO-TOPIS-3-2","roadLinkId":"DEMO-%s","segmentId":"%s",
                 "status":"blocked","computedAt":"2026-09-21T16:50:00Z",
                 "validUntil":"2026-09-21T19:00:00Z"}
                """;
        when(redis.value(MovementConditionsService.DEMO_ROAD_CONTROL_PREFIX + 3))
                .thenReturn(event.formatted(2, "BUS:100:A:B", "BUS:100:A:B"));
        Instant now = Instant.parse("2026-09-21T17:00:00Z");

        var shown =
                new MovementConditionsService(
                                schedules, routes, redis, new ObjectMapper(), false, 12, true)
                        .get(7L, 3L, now)
                        .roadBlock();
        assertThat(shown.available()).isTrue();
        assertThat(shown.source()).isEqualTo("DEMO_TOPIS");
        // 저장된 구간 문장이 없던 예전 키도 "가짜" 같은 말 없이 같은 모양으로 보인다.
        assertThat(shown.summary()).isEqualTo("[시연] 지금 버스 경로에 도로 통제가 있어요.");
        when(redis.value(MovementConditionsService.DEMO_ROAD_CONTROL_PREFIX + 3))
                .thenReturn(
                        event.formatted(2, "BUS:100:A:B", "BUS:100:A:B")
                                .replace(
                                        "\"status\"",
                                        "\"message\":\"A → B 구간 도로 통제로 262번 버스 운행에 차질이 있어요.\","
                                                + "\"status\""));
        assertThat(
                        new MovementConditionsService(
                                        schedules,
                                        routes,
                                        redis,
                                        new ObjectMapper(),
                                        false,
                                        12,
                                        true)
                                .get(7L, 3L, now)
                                .roadBlock()
                                .summary())
                .isEqualTo("[시연] A → B 구간 도로 통제로 262번 버스 운행에 차질이 있어요.");

        // 기능이 꺼져 있으면 키가 남아 있어도 보여주지 않는다.
        assertThat(
                        new MovementConditionsService(
                                        schedules, routes, redis, new ObjectMapper(), false, 12)
                                .get(7L, 3L, now)
                                .roadBlock()
                                .source())
                .isNotEqualTo("DEMO_TOPIS");

        // 통제는 일정마다 한 번이다. 제안 경로로 바꿔 입력 버전이 올라가도 막힌 도로는 그대로 막혀 있다(2026-09-26).
        when(redis.value(MovementConditionsService.DEMO_ROAD_CONTROL_PREFIX + 3))
                .thenReturn(event.formatted(1, "BUS:100:A:B", "BUS:100:A:B"));
        var service =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 12, true);
        assertThat(service.get(7L, 3L, now).roadBlock().source()).isEqualTo("DEMO_TOPIS");

        // 지금 경로에 없는 구간의 통제는 이 경로의 통제가 아니다.
        when(redis.value(MovementConditionsService.DEMO_ROAD_CONTROL_PREFIX + 3))
                .thenReturn(event.formatted(2, "BUS:999:X:Y", "BUS:999:X:Y"));
        assertThat(service.get(7L, 3L, now).roadBlock().source()).isNotEqualTo("DEMO_TOPIS");

        // 통제는 도로(두 정류장 사이) 단위다. 다른 번호 버스의 구간이라도 같은 두 정류장 사이면 이 경로에도 통제가 보인다(2026-09-26).
        when(redis.value(MovementConditionsService.DEMO_ROAD_CONTROL_PREFIX + 3))
                .thenReturn(
                        event.formatted(2, "BUS:999:A:B", "BUS:999:A:B")
                                .replace("\"status\"", "\"stopPair\":\"A:B\",\"status\""));
        assertThat(service.get(7L, 3L, now).roadBlock().source()).isEqualTo("DEMO_TOPIS");
    }

    @Test
    void 선택경로의구간과호선에연결된혼잡통제공지값만반환한다() {
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        MovementConditionsRedisReader redis = mock(MovementConditionsRedisReader.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "서울시청",
                                new BigDecimal("37.5665000"),
                                new BigDecimal("126.9780000")));

        SavedRoute route = SavedRoute.builder().baseDurationMin(30).selected(true).build();
        SavedRouteSegment bus =
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.BUS)
                        .fromName("A")
                        .toName("B")
                        .baseDurationMin(10)
                        .build();
        bus.addEdge(
                SavedRouteSegmentEdge.builder()
                        .edgeSeq(1)
                        .segmentId("BUS:100:A:B")
                        .mappingVersion("master-v1")
                        .policyVersion("policy-v1")
                        .build());
        route.addSegment(bus);
        route.addSegment(
                SavedRouteSegment.builder()
                        .seq(2)
                        .mode(TransportMode.SUBWAY)
                        .providerRouteKey("2호선")
                        .fromName("B")
                        .toName("C")
                        .baseDurationMin(20)
                        .build());
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.of(route));

        when(redis.value("ml:mapping:bus-city:active:master-v1"))
                .thenReturn("ml:mapping:bus-city:v1:master-v1:sha");
        when(redis.hashValue("ml:mapping:bus-city:v1:master-v1:sha", "BUS:100:A:B"))
                .thenReturn(
                        """
                        {"masterVersion":"master-v1","mappingStatus":"RESOLVED",
                         "primaryProviderAreaId":"POI001"}
                        """);
        when(redis.value("city:latest:area:POI001"))
                .thenReturn(
                        """
                        {"areaName":"시청","processingStatus":"VALID","congestionLevel":"BUSY",
                         "congestionScore":3,"observedAt":"2026-09-22T01:55:00+09:00",
                         "validUntil":"2026-09-22T02:15:00+09:00"}
                        """);
        when(redis.value("ml:mapping:bus-road:active:master-v1"))
                .thenReturn("ml:mapping:bus-road:v1:master-v1:sha");
        when(redis.hashValue("ml:mapping:bus-road:v1:master-v1:sha", "BUS:100:A:B"))
                .thenReturn(
                        """
                        {"masterVersion":"master-v1","mappingStatus":"RESOLVED",
                         "topisLinkNamespace":"SEOUL_ACCINFO_LINK","topisLinkIds":["L1"],
                         "roadPathValidFrom":"2026-09-01T00:00:00+09:00",
                         "roadPathValidUntil":"2026-10-01T00:00:00+09:00"}
                        """);
        when(redis.hashEntries("road:incidents:SEOUL_ACCINFO_LINK:L1"))
                .thenReturn(
                        Map.of(
                                "event",
                                """
                                {"providerIncidentId":"I1","incidentTypeCode":"A04",
                                 "startsAt":"2026-09-22T01:00:00+09:00",
                                 "collectedAt":"2026-09-22T01:58:00+09:00",
                                 "expiresEpoch":1790024400,"conflicted":false}
                                """));
        when(redis.value("traffic:latest:incident:I1"))
                .thenReturn(
                        """
                        {"observation":{"incidentMessage":"봉은교 양방향 전면통제"},
                         "validUntil":"2026-09-22T03:00:00+09:00"}
                        """);
        when(redis.scanValues(
                        "transit:latest:subway-alert:",
                        MovementConditionsService.MAX_SUBWAY_ALERT_KEYS))
                .thenReturn(
                        List.of(
                                new MovementConditionsRedisReader.KeyValue(
                                        "transit:latest:subway-alert:S1",
                                        """
                                        {"observation":{"alertLineName":"2호선","alertTitle":"운행 지연",
                                         "alertContent":"시설 점검으로 지연 중"},
                                         "occurredAt":"2026-09-22T01:57:00+09:00",
                                         "validUntil":"2026-09-22T03:00:00+09:00"}
                                        """)));
        var service =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 12);

        var result = service.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));

        assertThat(result.congestion().areaId()).isEqualTo("POI001");
        assertThat(result.congestion().level()).isEqualTo("BUSY");
        assertThat(result.roadBlock().available()).isTrue();
        assertThat(result.roadBlock().summary()).contains("양방향 전면통제");
        assertThat(result.roadBlock().mappingStatus()).isEqualTo("RESOLVED");
        assertThat(result.subwayDelay().available()).isTrue();
        assertThat(result.subwayDelay().routeLines()).containsExactly("2호선");
        assertThat(result.subwayDelay().summary()).contains("시설 점검");

        when(redis.hashValue("ml:mapping:bus-road:v1:master-v1:sha", "BUS:100:A:B"))
                .thenReturn(
                        """
                        {"masterVersion":"master-v1","mappingStatus":"UNMAPPED"}
                        """);
        when(redis.value("ml:mapping:bus-road-provisional:active:master-v1"))
                .thenReturn("ml:mapping:bus-road-provisional:v1:master-v1:sha");
        when(redis.hashValue("ml:mapping:bus-road-provisional:v1:master-v1:sha", "BUS:100:A:B"))
                .thenReturn(
                        """
                        {"masterVersion":"master-v1","mappingStatus":"PROVISIONAL",
                         "trafficLinkMasterVersion":"provisional-accinfo-input-v1",
                         "topisLinkNamespace":"SEOUL_ACCINFO_LINK","topisLinkIds":["L1"],
                         "roadPathValidFrom":"2026-09-01T00:00:00+09:00",
                         "roadPathValidUntil":"2026-10-01T00:00:00+09:00"}
                        """);
        var provisionalService =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), true, 12);
        var provisional = provisionalService.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));
        assertThat(provisional.roadBlock().available()).isTrue();
        assertThat(provisional.roadBlock().mappingStatus()).isEqualTo("PROVISIONAL");

        when(redis.hashEntries("road:incidents:SEOUL_ACCINFO_LINK:L1")).thenReturn(Map.of());
        when(redis.value("traffic:latest:incident-snapshot:all-current"))
                .thenReturn(
                        """
                        {"completeSnapshot":true,"linkMasterAvailable":true,
                         "linkNamespace":"SEOUL_ACCINFO_LINK","linkMappingStatus":"PROVISIONAL",
                         "linkMasterVersion":"provisional-accinfo-input-v1",
                         "collectedAt":"2026-09-22T01:58:00+09:00",
                         "validUntil":"2026-09-22T03:00:00+09:00",
                         "incidents":[{"providerIncidentId":"I1","matchedLinkId":"L1",
                           "startedAt":"2026-09-22T01:00:00+09:00"}]}
                        """);
        var snapshotFallback =
                provisionalService.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));
        assertThat(snapshotFallback.roadBlock().available()).isTrue();
        assertThat(snapshotFallback.roadBlock().mappingStatus()).isEqualTo("PROVISIONAL");
    }

    /** 2호선만 타는 경로. 지하철 칸만 본다. */
    private static MovementConditionsService subwayOnly(
            MovementConditionsRedisReader redis,
            List<MovementConditionsRedisReader.KeyValue> alerts) {
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "이대역",
                                new BigDecimal("37.5567000"),
                                new BigDecimal("126.9460000")));
        SavedRoute route = SavedRoute.builder().baseDurationMin(20).selected(true).build();
        route.addSegment(
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.SUBWAY)
                        .providerRouteKey("2호선")
                        .fromName("시청")
                        .toName("신촌")
                        .baseDurationMin(20)
                        .build());
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.of(route));
        when(redis.scanValues(
                        "transit:latest:subway-alert:",
                        MovementConditionsService.MAX_SUBWAY_ALERT_KEYS))
                .thenReturn(alerts);
        return new MovementConditionsService(
                schedules, routes, redis, new ObjectMapper(), false, 12);
    }

    /** 수집기가 저장하는 모양 그대로. 유효시각은 "올린 시각 + 10분" 이다. */
    private static MovementConditionsRedisReader.KeyValue notice(
            String id, String line, String title, String postedAt, String validUntil) {
        return new MovementConditionsRedisReader.KeyValue(
                "transit:latest:subway-alert:" + id,
                """
                {"observation":{"alertLineName":%s,"alertTitle":"%s","alertContent":"%s"},
                 "occurredAt":"%s","validUntil":"%s",
                 "collectedAt":"2026-09-22T21:55:00+09:00"}
                """
                        .formatted(
                                line == null ? "null" : "\"" + line + "\"",
                                title,
                                title,
                                postedAt,
                                validUntil));
    }

    /**
     * 지하철 공지는 올린 뒤 10분이 아니라, 같은 호선·역에 운행재개 공지가 올 때까지 보여준다(최대 2시간). 2026-09-22 2호선 이대역 무정차는 20:27 에
     * 공지되고 22:19 에 운행이 재개됐는데, 예전 규칙으로는 20:37 부터 "확인된 공지 없음" 이었다.
     */
    @Test
    void 지하철공지는운행재개공지가오기전까지보여준다() {
        var disruption =
                notice(
                        "A",
                        "2호선",
                        "2호선 이대역 안전문장애로 열차 무정차 통과중",
                        "2026-09-22T21:22:00+09:00",
                        "2026-09-22T21:32:00+09:00");
        var old =
                notice(
                        "OLD",
                        "2호선",
                        "2호선 합정역 열차 지연",
                        "2026-09-22T18:00:00+09:00",
                        "2026-09-22T18:10:00+09:00");
        Instant now = Instant.parse("2026-09-22T12:55:00Z"); // 21:55 KST

        var during =
                subwayOnly(mock(MovementConditionsRedisReader.class), List.of(disruption, old))
                        .get(7L, 3L, now)
                        .subwayDelay();
        assertThat(during.available()).isTrue();
        assertThat(during.summary()).contains("이대역");
        assertThat(during.alerts()).hasSize(1); // 4시간 전 공지는 끝난 것으로 본다

        var resumed =
                notice(
                        "B",
                        "2호선",
                        "2호선 이대역 열차 운행재개",
                        "2026-09-22T22:19:00+09:00",
                        "2026-09-22T22:29:00+09:00");
        var after =
                subwayOnly(mock(MovementConditionsRedisReader.class), List.of(disruption, resumed))
                        .get(7L, 3L, Instant.parse("2026-09-22T13:25:00Z")) // 22:25 KST
                        .subwayDelay();
        // 운행재개 공지는 따로 보여주지 않고 앞선 공지를 끝낸다.
        assertThat(after.available()).isFalse();
        assertThat(after.reason()).isEqualTo("NO_MATCHING_ACTIVE_ALERT");

        // 다른 역의 운행재개는 이대역 공지를 끝내지 않는다. 호선 칸이 빈 공지는 제목의 호선으로 맞춘다.
        var elsewhere =
                notice(
                        "C",
                        null,
                        "2호선 신촌역 열차 정상운행",
                        "2026-09-22T21:40:00+09:00",
                        "2026-09-22T21:50:00+09:00");
        var mixed =
                subwayOnly(
                                mock(MovementConditionsRedisReader.class),
                                List.of(disruption, elsewhere))
                        .get(7L, 3L, now)
                        .subwayDelay();
        assertThat(mixed.available()).isTrue();
        assertThat(mixed.alerts()).extracting("title").containsExactly("2호선 이대역 안전문장애로 열차 무정차 통과중");
    }

    /**
     * 사고 상세의 유효시각은 "사고 시작 + 10분" 이라 오래 이어지는 공사는 늘 만료로 보였다. 그래서 도로 칸에 실제 내용 대신 "선택 경로에 도로 돌발정보가
     * 확인됐어요" 만 떴다. 최근에 다시 받은 상세면 설명을 쓴다. 오래전에 받은 상세는 여전히 쓰지 않는다.
     */
    @Test
    void 오래이어지는사고도최근에받은설명이면도로칸에보여준다() {
        MovementConditionsRedisReader redis = mock(MovementConditionsRedisReader.class);
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SavedRouteRepository routes = mock(SavedRouteRepository.class);
        Schedule schedule = mock(Schedule.class);
        when(schedules.findById(3L)).thenReturn(Optional.of(schedule));
        when(schedule.isOwnedBy(7L)).thenReturn(true);
        when(schedule.getDestination())
                .thenReturn(
                        new LocationSnapshot(
                                "서울시청",
                                new BigDecimal("37.5665000"),
                                new BigDecimal("126.9780000")));
        SavedRoute route = SavedRoute.builder().baseDurationMin(30).selected(true).build();
        SavedRouteSegment bus =
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.BUS)
                        .fromName("A")
                        .toName("B")
                        .baseDurationMin(10)
                        .build();
        bus.addEdge(
                SavedRouteSegmentEdge.builder()
                        .edgeSeq(1)
                        .segmentId("BUS:100:A:B")
                        .mappingVersion("master-v1")
                        .policyVersion("policy-v1")
                        .build());
        route.addSegment(bus);
        when(routes.findSelectedWithSegments(3L)).thenReturn(Optional.of(route));
        when(redis.value("ml:mapping:bus-road:active:master-v1"))
                .thenReturn("ml:mapping:bus-road:v1:master-v1:sha");
        when(redis.hashValue("ml:mapping:bus-road:v1:master-v1:sha", "BUS:100:A:B"))
                .thenReturn(
                        """
                        {"masterVersion":"master-v1","mappingStatus":"RESOLVED",
                         "topisLinkNamespace":"SEOUL_ACCINFO_LINK","topisLinkIds":["L1"],
                         "roadPathValidFrom":"2026-09-01T00:00:00+09:00",
                         "roadPathValidUntil":"2026-10-01T00:00:00+09:00"}
                        """);
        when(redis.hashEntries("road:incidents:SEOUL_ACCINFO_LINK:L1"))
                .thenReturn(
                        Map.of(
                                "event",
                                """
                                {"providerIncidentId":"I1","incidentTypeCode":"A04",
                                 "startsAt":"2025-08-25T00:00:00+09:00",
                                 "collectedAt":"2026-09-22T01:58:00+09:00",
                                 "expiresEpoch":1790024400,"conflicted":false}
                                """));
        String detail =
                """
                {"observation":{"incidentMessage":"증산로 (신사오거리 → 새절역) 3차로 시설물보수"},
                 "occurredAt":"2025-08-25T00:00:00+09:00",
                 "validUntil":"2025-08-25T00:10:00+09:00",
                 "collectedAt":"%s"}
                """;
        when(redis.value("traffic:latest:incident:I1"))
                .thenReturn(detail.formatted("2026-09-22T01:50:00+09:00"));
        var service =
                new MovementConditionsService(
                        schedules, routes, redis, new ObjectMapper(), false, 12);
        Instant now = Instant.parse("2026-09-21T17:00:00Z"); // 02:00 KST

        assertThat(service.get(7L, 3L, now).roadBlock().summary()).contains("증산로");

        // 세 시간 전에 받은 설명은 쓰지 않는다. 통제는 있으니 일반 문장으로 남는다.
        when(redis.value("traffic:latest:incident:I1"))
                .thenReturn(detail.formatted("2026-09-21T23:00:00+09:00"));
        var stale = service.get(7L, 3L, now).roadBlock();
        assertThat(stale.available()).isTrue();
        assertThat(stale.summary()).doesNotContain("증산로");
    }
}
