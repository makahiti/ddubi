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
        var service = new MovementConditionsService(schedules, routes, redis, new ObjectMapper());

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
        var service = new MovementConditionsService(schedules, routes, redis, new ObjectMapper());

        var result = service.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));

        assertThat(result.weather().available()).isFalse();
        assertThat(result.weather().reason()).isEqualTo("WEATHER_STALE");
        assertThat(result.weather().precipitationMm()).isNull();
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
        when(redis.scanValues("transit:latest:subway-alert:", 300))
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
        var service = new MovementConditionsService(schedules, routes, redis, new ObjectMapper());

        var result = service.get(7L, 3L, Instant.parse("2026-09-21T17:00:00Z"));

        assertThat(result.congestion().areaId()).isEqualTo("POI001");
        assertThat(result.congestion().level()).isEqualTo("BUSY");
        assertThat(result.roadBlock().available()).isTrue();
        assertThat(result.roadBlock().summary()).contains("양방향 전면통제");
        assertThat(result.subwayDelay().available()).isTrue();
        assertThat(result.subwayDelay().routeLines()).containsExactly("2호선");
        assertThat(result.subwayDelay().summary()).contains("시설 점검");
    }
}
