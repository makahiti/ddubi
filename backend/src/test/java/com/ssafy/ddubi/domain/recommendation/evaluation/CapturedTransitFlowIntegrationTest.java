package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.service.KakaoRouteMapper;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 사용자가 보존한 실제 응답/공식 시간표를 읽는 명시적 로컬 Replay다. 외부 파일 경로가 필요하므로
 * 기본 CI에서는 실행하지 않는다. 인증 필터·등록 HTTP·실제 MySQL/Redis·Mapper·평가·저장/조회는
 * 실제 구현을 사용한다. 카카오 네트워크만 캡처로 대체하며 실시간 관측이나 ML 성공값을 조작하지 않는다.
 * 보고서는 target에만 쓰고 JWT/원본 경로 좌표를 보고서에 넣지 않는다.
 */
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="transit.replay.enabled", matches="true")
class CapturedTransitFlowIntegrationTest extends IntegrationTestSupport {
    @DynamicPropertySource static void replayProperties(DynamicPropertyRegistry properties) {
        properties.add("ddubi.subway-timing.timetable-path", () -> System.getProperty("transit.replay.timetable"));
        properties.add("ddubi.transit-mapping.subway-master-resource", () -> System.getProperty("transit.replay.master"));
        properties.add("ddubi.subway-timing.decomposition-verified", () -> false);
        properties.add("ddubi.bus-waiting.decomposition-verified", () -> false);
    }
    @Autowired ObjectMapper json;
    @Autowired KakaoRouteMapper mapper;
    @Autowired MlRoutePreview preview;
    @Autowired SubwayTimetable timetable;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublicTransitRouteClient routeClient;
    @Autowired MetropolitanBusCatalog busCatalog;
    @Autowired MetropolitanBusMasterService busMasterService;

    @Test void capturedCandidatesAndActualTimetableSurviveAuthenticatedScheduleHttpFlow() throws Exception {
        assertThat(timetable.data()).as(timetable.unavailableReason()).isNotNull();
        Path busFile = Path.of(System.getProperty("transit.replay.bus-master"));
        // 운영과 동일한 관리 적재 경계를 사용한다. 메모리만 주입하면 캐시의 마스터 FK가 없는 가짜 환경이 된다.
        busMasterService.importAndActivate(busFile,MetropolitanBusMaster.sha(Files.readAllBytes(busFile)));
        busCatalog.reload();
        Instant now = Instant.now();
        Instant departure = timetable.data().validFrom().plusSeconds(9 * 3600);
        assertThat(departure).as("현재 이후의 명시적 운행일을 사용해야 한다").isAfter(now);
        Path captures = Path.of(System.getProperty("transit.replay.responses"));
        var report = new LinkedHashMap<String,Object>();
        var files = new ArrayList<Map<String,Object>>();
        int candidates = 0, subwaySteps = 0, timedSteps = 0;
        RouteSearchResult first = null;
        // 1. 실제 응답 20개를 DTO → 공식 마스터 매핑 → 구간 평가까지 재생한다.
        for (int i = 1; i <= 20; i++) {
            var search = mapper.map(json.readValue(captures.resolve(i + ".json").toFile(), KakaoPublicTransitResponse.class),now);
            if (i == 1) first = search;
            var comparison = preview.compare(search.candidates(),new RouteEvidenceAssembler.Snapshot(Map.of(),List.of()),
                    now,departure,departure.plusSeconds(14400));
            candidates += search.candidates().size();
            subwaySteps += comparison.subwayPredictions().size();
            long available = comparison.subwayPredictions().stream().filter(p -> p.estimate().waitSeconds() != null).count();
            timedSteps += (int) available;
            assertThat(comparison.subwayPredictions()).allMatch(p -> !p.applied());
            assertThat(comparison.waitPredictions()).allMatch(p -> !p.applied());
            assertThat(comparison.fastest()).isNotNull();
            var minimum = comparison.candidates().stream().map(c -> c.result().estimatedArrivalAt())
                    .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElseThrow();
            assertThat(comparison.fastest().estimatedArrivalAt()).isEqualTo(minimum);
            files.add(Map.of("capture",i,"candidates",search.candidates().size(),"timedSubwaySteps",available,
                    "subwaySteps",comparison.subwayPredictions().size(),"fastest",comparison.fastest().journeyKey(),
                    "subwayReasons",comparison.subwayPredictions().stream().flatMap(p -> p.estimate().reasons().stream()).distinct().sorted().toList()));
        }
        // Matcher 반환만으로 성공 처리하지 않는다. 파일 버전 등록과 실제 캐시 저장도 확인한다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_segment_mapping WHERE mapping_status='EXACT'",Integer.class)).isPositive();
        assertThat(candidates).isEqualTo(288);
        assertThat(subwaySteps).isEqualTo(248);
        assertThat(timedSteps).isPositive();
        // 2. 첫 캡처의 실제 출발/도착 좌표로 일정을 HTTP 등록한다. 인증 토큰은 로그/보고서에 기록하지 않는다.
        long owner = users.save(User.builder().email("captured-flow@example.test").nickname("캡처 검증").build()).getId();
        String bearer = "Bearer " + tokens.createAccessToken(owner);
        var body = Map.of("title","실제 응답 Replay", "origin",Map.of("name","출발","lat",37.5666,"lng",126.9784),
                "destination",Map.of("name","도착","lat",37.5709,"lng",127.0019),
                "plannedDepartAt",LocalDateTime.ofInstant(departure,ZoneId.of("Asia/Seoul")).toString(),
                "arriveBy",LocalDateTime.ofInstant(departure.plusSeconds(3600),ZoneId.of("Asia/Seoul")).toString());
        String created = http.perform(post("/api/v1/schedules").header("Authorization",bearer)
                .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).path("data").path("id").asLong();
        assertThat(id).isPositive();
        when(routeClient.search(any())).thenReturn(first);
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations",id).header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.preview.status").value("PREVIEW"))
                .andReturn().getResponse().getContentAsString();
        var expected = json.readTree(evaluated).path("data");
        assertThat(expected.path("preview").path("fastest").isObject()).isTrue();
        // 3. 프론트가 읽는 latest API와 DB 왕복에서 wait/source/reasons가 보존되는지 검증한다.
        String latest = http.perform(get("/api/v1/schedules/{id}/recommendations/latest",id).header("Authorization",bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(latest).path("data").path("result")).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route_segment_edge",Integer.class)).isPositive();
        // 검증용 결과도 추천 행으로 남는다(2026-09-14). 프론트가 근거 화면을 열 수 있어야 한다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isEqualTo(1);
        long recommendationId = expected.path("recommendationId").asLong();
        assertThat(recommendationId).isPositive();
        http.perform(get("/api/v1/recommendations/{id}/explanation",recommendationId).header("Authorization",bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preview").value(true))
                .andExpect(jsonPath("$.data.productionReady").value(false))
                .andExpect(jsonPath("$.data.confidence").value("LOW"));
        http.perform(post("/api/v1/schedules/{id}/recommendations",id).header("Authorization",bearer)).andExpect(status().isOk());
        verify(routeClient,times(1)).search(any());
        http.perform(get("/api/v1/schedules/{id}/recommendations/latest",id)).andExpect(status().isUnauthorized());
        report.put("mode","CAPTURED_RESPONSE_REPLAY_NOT_LIVE_API");
        report.put("candidates",candidates); report.put("subwaySteps",subwaySteps); report.put("timedSubwaySteps",timedSteps);
        report.put("timetableVersion",timetable.data().version()); report.put("httpRegistrationEvaluationRead",true);
        report.put("waitApplied",false); report.put("liveTrainCrosswalk",false); report.put("files",files);
        Path output = Path.of("target/transit-timing"); Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("flow-report.json").toFile(),report);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("schedule-preview.json").toFile(),expected);
    }
}
