package com.ssafy.ddubi.domain.route.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.repository.RecommendationRepository;
import com.ssafy.ddubi.domain.route.entity.RouteProvider;
import com.ssafy.ddubi.domain.route.entity.RouteRequest;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.repository.RouteRequestRepository;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.route.service.RouteQueryService;
import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 경로 목록·상세·지도 조회를 HTTP 로 확인한다.
 *
 * <p>경로는 저장소에 직접 넣는다. 평가 흐름을 태우면 좌표가 카카오 응답에서 오는 값이라 <b>무엇이 돌아와야 하는지 테스트가 알 수 없다.</b> 여기서 검증하려는 것은
 * 계산이 아니라 "저장된 것이 그대로 나오는가" 다.
 *
 * <p>소유권을 특히 본다. {@code routeId} 는 순번이라 남의 것을 찍어 볼 수 있고, 경로에는 사용자가 직접 달려 있지 않아 일정을 거쳐야 한다 — 빠뜨리기 쉬운
 * 자리다.
 */
@AutoConfigureMockMvc
class RouteQueryApiTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 구간 하나의 폴리라인. 저장한 것이 그대로 돌아오는지 보려고 값을 고정한다. */
    private static final String GEOMETRY =
            """
            [{"longitude":127.0276,"latitude":37.4979},
             {"longitude":127.0310,"latitude":37.4990},
             {"longitude":127.0364,"latitude":37.5006}]
            """;

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository schedules;
    @Autowired RouteRequestRepository routeRequests;
    @Autowired SavedRouteRepository savedRoutes;
    @Autowired RecommendationRepository recommendations;
    @Autowired JwtTokenProvider tokens;

    private long owner;
    private String bearer;
    private long scheduleId;
    private long selectedRouteId;

    @BeforeEach
    void fixture() {
        owner =
                users.save(User.builder().email("route-api@example.test").nickname("경로 조회").build())
                        .getId();
        bearer = "Bearer " + tokens.createAccessToken(owner);
        scheduleId = newSchedule("경로 조회");

        Schedule schedule = schedules.findById(scheduleId).orElseThrow();
        RouteRequest request =
                routeRequests.save(
                        RouteRequest.builder()
                                .schedule(schedule)
                                .provider(RouteProvider.KAKAO)
                                .rawResponse(null)
                                .requestedAt(LocalDateTime.now(KST))
                                .build());

        // 1순위: 도보 → 버스 두 구간. 버스 구간에만 좌표가 있다.
        SavedRoute selected =
                SavedRoute.builder()
                        .schedule(schedule)
                        .routeRequest(request)
                        .providerPathId("path-1")
                        .baseDurationMin(25)
                        .selected(true)
                        .rankNo(1)
                        .build();
        selected.addSegment(
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.WALK)
                        .fromName("집")
                        .toName("강남역")
                        .baseDurationMin(5)
                        .geometry(null)
                        .build());
        selected.addSegment(
                SavedRouteSegment.builder()
                        .seq(2)
                        .mode(TransportMode.BUS)
                        .providerRouteKey("146")
                        .fromName("강남역")
                        .toName("역삼역")
                        .baseDurationMin(20)
                        .geometry(GEOMETRY)
                        .build());
        selectedRouteId = savedRoutes.save(selected).getId();

        SavedRoute alternative =
                SavedRoute.builder()
                        .schedule(schedule)
                        .routeRequest(request)
                        .providerPathId("path-2")
                        .baseDurationMin(31)
                        .selected(false)
                        .rankNo(2)
                        .build();
        alternative.addSegment(
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.SUBWAY)
                        .providerRouteKey("2호선")
                        .fromName("강남역")
                        .toName("역삼역")
                        .baseDurationMin(31)
                        .geometry(null)
                        .build());
        savedRoutes.save(alternative);
    }

    private long newSchedule(String title) {
        return scheduleService
                .create(
                        owner,
                        new ScheduleCreateRequest(
                                title,
                                new LocationRequest(
                                        "강남",
                                        new BigDecimal("37.4979"),
                                        new BigDecimal("127.0276")),
                                new LocationRequest(
                                        "역삼",
                                        new BigDecimal("37.5006"),
                                        new BigDecimal("127.0364")),
                                LocalDateTime.now(KST).plusHours(3),
                                null,
                                null,
                                null))
                .id();
    }

    /** 새 검색분 후보 하나를 저장한다. 순위는 인자로 받아 옛 검색분과 겹치는 상황을 만든다. */
    private SavedRoute saveCandidate(
            RouteRequest request, String pathId, int rank, boolean selected) {
        Schedule schedule = schedules.findById(scheduleId).orElseThrow();
        SavedRoute route =
                SavedRoute.builder()
                        .schedule(schedule)
                        .routeRequest(request)
                        .providerPathId(pathId)
                        .baseDurationMin(20 + rank)
                        .selected(selected)
                        .rankNo(rank)
                        .build();
        route.addSegment(
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.SUBWAY)
                        .providerRouteKey("2호선")
                        .fromName("강남역")
                        .toName("역삼역")
                        .baseDurationMin(20 + rank)
                        .geometry(null)
                        .build());
        return savedRoutes.save(route);
    }

    private RouteRequest newRequest() {
        return routeRequests.save(
                RouteRequest.builder()
                        .schedule(schedules.findById(scheduleId).orElseThrow())
                        .provider(RouteProvider.KAKAO)
                        .rawResponse(null)
                        .requestedAt(LocalDateTime.now(KST))
                        .build());
    }

    @Test
    @DisplayName("다시 계산하면 목록은 마지막 검색분만 준다")
    void listsOnlyTheLatestSearch() throws Exception {
        // 두 번째 검색: 선택은 새 후보로 옮겨간다(실제 평가 흐름과 같다).
        SavedRoute previous = savedRoutes.findById(selectedRouteId).orElseThrow();
        previous.unselect();
        savedRoutes.save(previous);
        RouteRequest second = newRequest();
        long newSelected = saveCandidate(second, "path-3", 1, true).getId();
        long newAlternative = saveCandidate(second, "path-4", 2, false).getId();

        http.perform(
                        get("/api/v1/schedules/{id}/routes", scheduleId)
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                // 옛 검색분 2건이 섞이면 순위가 1,2,1,2 로 반복된다. 마지막 검색분만 나와야 한다.
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath("$.data[*].routeId")
                                .value(
                                        org.hamcrest.Matchers.containsInAnyOrder(
                                                (int) newSelected, (int) newAlternative)))
                .andExpect(jsonPath("$.data[0].routeId").value(newSelected))
                .andExpect(jsonPath("$.data[0].selected").value(true));
    }

    @Test
    @DisplayName("사용자가 고른 경로는 옛 검색분이어도 목록에 남는다")
    void keepsTheSelectedRouteEvenFromAnOlderSearch() throws Exception {
        RouteRequest second = newRequest();
        saveCandidate(second, "path-3", 1, false);

        http.perform(
                        get("/api/v1/schedules/{id}/routes", scheduleId)
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath("$.data[?(@.routeId == %d)].selected".formatted(selectedRouteId))
                                .value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    @DisplayName("정리 대상에서 선택된 경로와 지난 추천이 가리키는 경로는 빠진다")
    void staleCandidatesExcludeSelectedAndRecommendedRoutes() {
        Schedule schedule = schedules.findById(scheduleId).orElseThrow();
        SavedRoute recommended =
                savedRoutes.findAll().stream()
                        .filter(route -> "path-2".equals(route.getProviderPathId()))
                        .findFirst()
                        .orElseThrow();
        recommendations.save(
                Recommendation.builder()
                        .schedule(schedule)
                        .route(recommended)
                        .departAt(LocalDateTime.now(KST))
                        .eta(LocalDateTime.now(KST).plusMinutes(30))
                        .durationMin(30)
                        .bufferMin(5)
                        .percentileUsed((byte) 50)
                        .confidence(Confidence.LOW)
                        .reasons("[]")
                        .segmentStatusSnapshot("[]")
                        .dataCheckedAt(LocalDateTime.now(KST))
                        .policyVersion("v1")
                        .triggerType(TriggerType.REFRESH)
                        .build());

        RouteRequest second = newRequest();
        saveCandidate(second, "path-3", 1, false);

        // 유예 시간을 무시하도록 기준 시각을 미래로 둔다. 여기서 보려는 것은 선택·추천 참조 예외다.
        var stale =
                savedRoutes.findStaleCandidates(
                        scheduleId, second.getId(), LocalDateTime.now(KST).plusMinutes(1));

        // 옛 검색분 2건 중 하나는 선택됨, 하나는 지난 추천이 가리킨다. 지울 것이 없다.
        assertThat(stale).isEmpty();
    }

    @Test
    @DisplayName("방금 전 검색분의 후보는 유예 시간 동안 정리 대상에서 빠진다")
    void recentlyReplacedCandidatesSurviveTheGracePeriod() {
        RouteRequest second = newRequest();
        saveCandidate(second, "path-3", 1, false);
        LocalDateTime now = LocalDateTime.now(KST);

        // 옛 검색분의 path-2 는 선택도 아니고 추천 참조도 없지만, 방금 저장된 것이라 아직 지우지 않는다.
        assertThat(
                        savedRoutes.findStaleCandidates(
                                scheduleId,
                                second.getId(),
                                now.minus(SavedRouteRepository.STALE_CANDIDATE_GRACE)))
                .isEmpty();
        // 유예 시간이 지났다고 보면 그때 정리 대상이 된다.
        assertThat(savedRoutes.findStaleCandidates(scheduleId, second.getId(), now.plusMinutes(1)))
                .extracting(SavedRoute::getProviderPathId)
                .containsExactly("path-2");
    }

    @Test
    @DisplayName("목록은 순위 순으로 요약만 준다")
    void listsCandidatesByRank() throws Exception {
        http.perform(
                        get("/api/v1/schedules/{id}/routes", scheduleId)
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data[0].rankNo").value(1))
                .andExpect(jsonPath("$.data[0].displayRank").value(1))
                .andExpect(jsonPath("$.data[0].selected").value(true))
                .andExpect(jsonPath("$.data[0].baseDurationMin").value(25))
                .andExpect(jsonPath("$.data[0].fromName").value("집"))
                .andExpect(jsonPath("$.data[0].toName").value("역삼역"))
                .andExpect(jsonPath("$.data[0].segmentCount").value(2))
                .andExpect(
                        jsonPath("$.data[0].modes")
                                .value(org.hamcrest.Matchers.contains("WALK", "BUS")))
                .andExpect(jsonPath("$.data[1].rankNo").value(2))
                .andExpect(jsonPath("$.data[1].displayRank").value(2))
                .andExpect(jsonPath("$.data[1].selected").value(false))
                // 목록은 구간 전체를 담지 않는다 — 후보가 늘면 응답만 커지고 목록 화면은 안 쓴다.
                .andExpect(jsonPath("$.data[0].segments").doesNotExist());
    }

    @Test
    @DisplayName("상세는 구간을 주고 좌표는 주지 않는다")
    void detailHasSegmentsWithoutGeometry() throws Exception {
        http.perform(get("/api/v1/routes/{id}", selectedRouteId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
                .andExpect(jsonPath("$.data.displayRank").value(1))
                .andExpect(jsonPath("$.data.providerPathId").value("path-1"))
                .andExpect(jsonPath("$.data.segments.length()").value(2))
                .andExpect(jsonPath("$.data.segments[0].seq").value(1))
                .andExpect(jsonPath("$.data.segments[0].mode").value("WALK"))
                .andExpect(jsonPath("$.data.segments[1].mode").value("BUS"))
                .andExpect(jsonPath("$.data.segments[1].providerRouteKey").value("146"))
                .andExpect(jsonPath("$.data.segments[1].baseDurationMin").value(20))
                .andExpect(jsonPath("$.data.segments[1].coordinates").doesNotExist())
                .andExpect(jsonPath("$.data.segments[1].geometry").doesNotExist());
    }

    @Test
    @DisplayName("선택 경로의 원본 rankNo가 커도 목록과 상세의 표시 순위는 1이다")
    void selectedRouteKeepsDisplayRankOneAcrossListAndDetail() throws Exception {
        SavedRoute selected = savedRoutes.findById(selectedRouteId).orElseThrow();
        selected.updateRank(20);
        savedRoutes.saveAndFlush(selected);

        http.perform(
                        get("/api/v1/schedules/{id}/routes", scheduleId)
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data[0].rankNo").value(20))
                .andExpect(jsonPath("$.data[0].displayRank").value(1));

        http.perform(get("/api/v1/routes/{id}", selectedRouteId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rankNo").value(20))
                .andExpect(jsonPath("$.data.displayRank").value(1));
    }

    @Test
    @DisplayName("지도는 저장한 좌표를 그대로 주고 bounds 를 계산한다")
    void mapReturnsStoredCoordinatesAndBounds() throws Exception {
        http.perform(
                        get("/api/v1/routes/{id}/map", selectedRouteId)
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data.segments.length()").value(2))
                // 좌표가 없는 구간은 빈 배열이다. 없는 좌표를 지어내지 않는다.
                .andExpect(jsonPath("$.data.segments[0].mode").value("WALK"))
                .andExpect(jsonPath("$.data.segments[0].coordinates").isEmpty())
                .andExpect(jsonPath("$.data.segments[1].coordinates.length()").value(3))
                .andExpect(jsonPath("$.data.segments[1].coordinates[0].longitude").value(127.0276))
                .andExpect(jsonPath("$.data.segments[1].coordinates[0].latitude").value(37.4979))
                .andExpect(jsonPath("$.data.segments[1].coordinates[2].longitude").value(127.0364))
                .andExpect(jsonPath("$.data.bounds.minLat").value(37.4979))
                .andExpect(jsonPath("$.data.bounds.minLng").value(127.0276))
                .andExpect(jsonPath("$.data.bounds.maxLat").value(37.5006))
                .andExpect(jsonPath("$.data.bounds.maxLng").value(127.0364));
    }

    @Test
    @DisplayName("좌표가 하나도 없으면 bounds 는 null 이다")
    void boundsIsNullWhenNothingToDraw() throws Exception {
        String listed =
                http.perform(
                                get("/api/v1/schedules/{id}/routes", scheduleId)
                                        .header("Authorization", bearer))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long withoutGeometry = json.readTree(listed).path("data").get(1).path("routeId").asLong();

        http.perform(
                        get("/api/v1/routes/{id}/map", withoutGeometry)
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                // 0,0 으로 채우면 화면이 아프리카 앞바다를 비춘다.
                .andExpect(jsonPath("$.data.bounds").doesNotExist())
                .andExpect(jsonPath("$.data.segments[0].coordinates").isEmpty());
    }

    /**
     * 좌표가 깨져 있어도 조회는 성공해야 한다. 좌표는 지도를 그리는 부가 정보라, 한 구간이 깨졌다고 경로 안내 전체를 막을 이유가 없다.
     *
     * <p>넣는 값이 "깨진 JSON" 이 아니라 <b>JSON 은 맞는데 모양이 다른 것</b>인 이유: {@code geometry} 는 MySQL {@code
     * json} 컬럼이라 문법이 틀린 문자열은 INSERT 단계에서 거부된다. 실제로 일어날 수 있는 손상은 제공자 응답 형식이 바뀌어 <b>다른 모양의 JSON</b> 이
     * 들어오는 경우다.
     *
     * <p>이 경우가 특히 위험했다. 기본 ObjectMapper 는 모르는 필드를 무시하므로 {@code {"x":1,"y":2}} 가 예외 없이 {@code (0.0,
     * 0.0)} 좌표가 된다 — 지도가 기니만 앞바다로 선을 긋고 bounds 가 지구 반대편까지 늘어난다. 엄격하게 읽어 걸러 낸다.
     */
    @Test
    @DisplayName("좌표가 깨져 있어도 500 이 아니라 빈 배열이다")
    void brokenGeometryDegradesToEmptyInsteadOfFailing() throws Exception {
        Schedule schedule = schedules.findById(scheduleId).orElseThrow();
        RouteRequest request =
                routeRequests.save(
                        RouteRequest.builder()
                                .schedule(schedule)
                                .provider(RouteProvider.KAKAO)
                                .rawResponse(null)
                                .requestedAt(LocalDateTime.now(KST))
                                .build());
        SavedRoute broken =
                SavedRoute.builder()
                        .schedule(schedule)
                        .routeRequest(request)
                        .providerPathId("path-broken")
                        .baseDurationMin(10)
                        .selected(false)
                        .rankNo(3)
                        .build();
        // ① 모양이 다른 객체 — 무시하면 (0,0) 이 된다
        broken.addSegment(
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.BUS)
                        .fromName("A")
                        .toName("B")
                        .baseDurationMin(5)
                        .geometry("[{\"x\":127.0,\"y\":37.5}]")
                        .build());
        // ② 배열이 아닌 GeoJSON 스타일
        broken.addSegment(
                SavedRouteSegment.builder()
                        .seq(2)
                        .mode(TransportMode.BUS)
                        .fromName("B")
                        .toName("C")
                        .baseDurationMin(5)
                        .geometry("{\"type\":\"LineString\",\"coordinates\":[[127.0,37.5]]}")
                        .build());
        // ③ 파싱은 되지만 지구 밖 — 한 점만 섞여도 bounds 가 못 쓰게 된다
        broken.addSegment(
                SavedRouteSegment.builder()
                        .seq(3)
                        .mode(TransportMode.BUS)
                        .fromName("C")
                        .toName("D")
                        .baseDurationMin(5)
                        .geometry(
                                "[{\"longitude\":127.0,\"latitude\":37.5},{\"longitude\":999,\"latitude\":999}]")
                        .build());
        // ④ 정확히 (0,0) — JSON 도 타입도 맞고 WGS84 범위 안이라 앞의 두 겹을 다 통과한다.
        //    필드 이름이 어긋나 기본값 0 이 채워졌을 때 나오는 모양이라 따로 막는다.
        broken.addSegment(
                SavedRouteSegment.builder()
                        .seq(4)
                        .mode(TransportMode.BUS)
                        .fromName("D")
                        .toName("E")
                        .baseDurationMin(5)
                        .geometry(
                                "[{\"longitude\":127.0,\"latitude\":37.5},{\"longitude\":0,\"latitude\":0}]")
                        .build());
        long brokenId = savedRoutes.save(broken).getId();

        var captured = captureServiceLogs();
        try {
            http.perform(get("/api/v1/routes/{id}/map", brokenId).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.segments.length()").value(4))
                    .andExpect(jsonPath("$.data.segments[0].coordinates").isEmpty())
                    .andExpect(jsonPath("$.data.segments[1].coordinates").isEmpty())
                    .andExpect(jsonPath("$.data.segments[2].coordinates").isEmpty())
                    .andExpect(jsonPath("$.data.segments[3].coordinates").isEmpty())
                    .andExpect(jsonPath("$.data.bounds").doesNotExist());
        } finally {
            detachServiceLogs(captured);
        }

        // 조용히 비면 "왜 선이 안 그려지지" 를 추적할 수 없다. 운영자가 볼 신호가 남아야 한다.
        var messages =
                captured.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList();
        assertThat(messages)
                .as("파싱 실패 2건 + 범위 위반 2건")
                .filteredOn(message -> message.contains("읽지 못했습니다"))
                .hasSize(2);
        assertThat(messages).filteredOn(message -> message.contains("지구 밖입니다")).hasSize(2);
        assertThat(messages)
                .allMatch(
                        message -> message.contains("routeSegmentId="),
                        "어느 구간인지 없으면 로그를 보고도 찾아갈 수 없다");

        // 좌표만 못 읽었을 뿐 경로 안내는 멀쩡해야 한다.
        http.perform(get("/api/v1/routes/{id}", brokenId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.segments.length()").value(4))
                .andExpect(jsonPath("$.data.segments[0].fromName").value("A"));
    }

    private ListAppender<ILoggingEvent> captureServiceLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(RouteQueryService.class)).addAppender(appender);
        return appender;
    }

    private void detachServiceLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(RouteQueryService.class)).detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("남의 경로는 볼 수 없다 — routeId 는 순번이라 찍어 볼 수 있다")
    void routesAreOwnedByTheScheduleOwner() throws Exception {
        long stranger =
                users.save(User.builder().email("stranger@example.test").nickname("남").build())
                        .getId();
        String theirs = "Bearer " + tokens.createAccessToken(stranger);

        http.perform(get("/api/v1/routes/{id}", selectedRouteId).header("Authorization", theirs))
                .andExpect(status().isForbidden());
        http.perform(
                        get("/api/v1/routes/{id}/map", selectedRouteId)
                                .header("Authorization", theirs))
                .andExpect(status().isForbidden());
        http.perform(
                        get("/api/v1/schedules/{id}/routes", scheduleId)
                                .header("Authorization", theirs))
                .andExpect(status().isForbidden());

        http.perform(get("/api/v1/routes/{id}", selectedRouteId))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/routes/{id}/map", selectedRouteId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 두 사용자가 동시에 자기 경로를 읽고, 서로의 경로를 찔러 본다.
     *
     * <p>순차 테스트로도 소유권 자체는 잡힌다. 여기서 보려는 것은 <b>동시에 돌 때도 섞이지 않는가</b> 다 — 소유자 판정에 쓰는 값이 요청마다 독립적이어야 한다.
     * 사용자 정보를 필드나 캐시에 얹는 흔한 실수가 있으면 한쪽 응답에 다른 쪽 데이터가 섞인다.
     */
    @Test
    @DisplayName("두 사용자가 동시에 조회해도 서로의 경로가 섞이지 않는다")
    void concurrentUsersStayIsolated() throws Exception {
        long other =
                users.save(User.builder().email("other@example.test").nickname("다른 사람").build())
                        .getId();
        String otherBearer = "Bearer " + tokens.createAccessToken(other);
        long otherSchedule =
                scheduleService
                        .create(
                                other,
                                new ScheduleCreateRequest(
                                        "남의 일정",
                                        new LocationRequest(
                                                "강남",
                                                new BigDecimal("37.4979"),
                                                new BigDecimal("127.0276")),
                                        new LocationRequest(
                                                "역삼",
                                                new BigDecimal("37.5006"),
                                                new BigDecimal("127.0364")),
                                        LocalDateTime.now(KST).plusHours(4),
                                        null,
                                        null,
                                        null))
                        .id();
        Schedule theirSchedule = schedules.findById(otherSchedule).orElseThrow();
        RouteRequest theirRequest =
                routeRequests.save(
                        RouteRequest.builder()
                                .schedule(theirSchedule)
                                .provider(RouteProvider.KAKAO)
                                .rawResponse(null)
                                .requestedAt(LocalDateTime.now(KST))
                                .build());
        SavedRoute theirRoute =
                SavedRoute.builder()
                        .schedule(theirSchedule)
                        .routeRequest(theirRequest)
                        .providerPathId("path-other")
                        .baseDurationMin(40)
                        .selected(true)
                        .rankNo(1)
                        .build();
        theirRoute.addSegment(
                SavedRouteSegment.builder()
                        .seq(1)
                        .mode(TransportMode.SUBWAY)
                        .fromName("남의출발")
                        .toName("남의도착")
                        .baseDurationMin(40)
                        .geometry(null)
                        .build());
        long otherRouteId = savedRoutes.save(theirRoute).getId();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            // 각자 자기 것을 읽고, 곧바로 남의 것을 찔러 본다. 두 스레드를 같은 순간에 출발시킨다.
            java.util.concurrent.Callable<String> mine =
                    probe(ready, start, bearer, scheduleId, otherRouteId);
            java.util.concurrent.Callable<String> theirs =
                    probe(ready, start, otherBearer, otherSchedule, selectedRouteId);

            var first = pool.submit(mine);
            var second = pool.submit(theirs);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .as("내 목록에는 내 구간만, 남의 경로는 403")
                    .isEqualTo("집|403");
            assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .as("남의 목록에는 남의 구간만, 내 경로는 403")
                    .isEqualTo("남의출발|403");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    /** 자기 목록의 첫 출발지 이름과, 남의 경로를 찔렀을 때의 상태코드를 {@code 이름|코드} 로 돌려준다. */
    private java.util.concurrent.Callable<String> probe(
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start,
            String token,
            long ownSchedule,
            long foreignRoute) {
        return () -> {
            ready.countDown();
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("start timeout");
            }
            String listed =
                    http.perform(
                                    get("/api/v1/schedules/{id}/routes", ownSchedule)
                                            .header("Authorization", token))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String fromName = json.readTree(listed).path("data").get(0).path("fromName").asText();
            int forbidden =
                    http.perform(
                                    get("/api/v1/routes/{id}", foreignRoute)
                                            .header("Authorization", token))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            return fromName + "|" + forbidden;
        };
    }

    @Test
    @DisplayName("없는 경로는 404, 평가 전 일정은 빈 배열 — 둘은 다른 상황이다")
    void missingRouteIs404ButUnevaluatedScheduleIsEmpty() throws Exception {
        http.perform(get("/api/v1/routes/{id}", 999_999L).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/routes/{id}/map", 999_999L).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/schedules/{id}/routes", 999_999L).header("Authorization", bearer))
                .andExpect(status().isNotFound());

        long fresh = newSchedule("평가 전");
        String empty =
                http.perform(
                                get("/api/v1/schedules/{id}/routes", fresh)
                                        .header("Authorization", bearer))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(json.readTree(empty).path("success").asBoolean())
                .as("경로가 없는 것은 실패가 아니다 — 아직 계산 전일 뿐이다")
                .isTrue();
    }
}
