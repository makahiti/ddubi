package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.repository.RecommendationRepository;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.mapping.RealtimeMappingCoverage;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import com.ssafy.ddubi.domain.route.model.FareRange;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.model.RouteSegment;
import com.ssafy.ddubi.domain.route.model.RouteType;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.schedule.entity.Importance;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 추천 계산 전체 흐름. 고정하려는 것:
 *
 * <ul>
 *   <li>도착 시각에서 출발 시각을 거꾸로 구하고, 여유만큼 앞당길 것</li>
 *   <li>후보 중 <b>가장 늦게 나가도 되는</b> 것을 1순위로 고를 것</li>
 *   <li>경로·구간·edge 가 계산에 쓴 것과 같은 모양으로 저장될 것</li>
 *   <li>추천이 불변일 것 — 다시 부르면 덮어쓰지 않고 행이 는다(도메인 규칙 7)</li>
 *   <li>남의 일정은 403 일 것</li>
 *   <li>지금 상태(예측·기준선 없음)에서 confidence 가 LOW 로 정직하게 나올 것</li>
 * </ul>
 */
class RecommendationIntegrationTest extends IntegrationTestSupport {

    private static final String EDGE_1 = "BUS:100100609:123456:123789";
    private static final String EDGE_2 = "BUS:100100609:123789:123900";

    /** 2026-09-15(화) 09:00 도착. */
    private static final LocalDateTime ARRIVE_BY = LocalDateTime.of(2026, 9, 15, 9, 0);

    @MockitoBean
    private PublicTransitRouteClient routeClient;

    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private RecommendationRepository recommendationRepository;
    @Autowired
    private SavedRouteRepository savedRouteRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;


    /**
     * 지연 로딩 컬렉션을 읽기 위한 것. 이 테스트 클래스는 일부러 @Transactional 을 쓰지 않는데
     * (DatabaseCleaner 주석 참고 — 커밋 시점의 DB 동작을 봐야 한다), 그러면 저장된 경로의
     * 구간·edge 를 세션 밖에서 읽게 된다. 검증할 때만 세션을 연다.
     */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
    private User owner;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .email("owner@ddubi.test")
                .nickname("주인")
                .build());
        schedule = scheduleRepository.save(Schedule.builder()
                .user(owner)
                .title("면접")
                .origin(new LocationSnapshot("집", bd("37.5010"), bd("127.0396")))
                .destination(new LocationSnapshot("회사", bd("37.5665"), bd("126.9780")))
                .arriveBy(ARRIVE_BY)
                .importance(Importance.HIGH)
                .build());
    }

    // ------------------------------------------------------------------
    // 출발 시각 역산
    // ------------------------------------------------------------------

    @Test
    @DisplayName("도착 시각에서 소요시간과 안전 여유를 빼 출발 시각을 구한다")
    void solvesDepartureFromArrival() {
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1)));

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        // 예측·기준선이 없으니 경로 API 시간 1800초(30분) 그대로 + 여유 5분
        assertThat(recommendation.getDurationMin()).isEqualTo(30);
        assertThat(recommendation.getBufferMin()).isEqualTo(5);
        assertThat(recommendation.getDepartAt()).isEqualTo(ARRIVE_BY.minusMinutes(35));
        assertThat(recommendation.getEta())
                .as("eta 는 여유를 뺀 실제 도착 예정이라 arrive_by 보다 여유만큼 이르다")
                .isEqualTo(ARRIVE_BY.minusMinutes(5));
    }

    @Test
    @DisplayName("지금 상태에서는 confidence 가 LOW 로 나온다 — 예측도 기준선도 없다")
    void confidenceIsLowToday() {
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1)));

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        assertThat(recommendation.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(recommendation.getPercentileUsed()).isEqualTo((byte) 50);
    }

    // ------------------------------------------------------------------
    // 후보 비교
    // ------------------------------------------------------------------

    @Test
    @DisplayName("가장 늦게 나가도 되는 후보를 1순위로 고른다")
    void picksCandidateWithLatestDeparture() {
        givenCandidates(
                candidate(0, 3000, busStep(1, 3000, EDGE_1)),    // 50분
                candidate(1, 1200, busStep(1, 1200, EDGE_2)));   // 20분 — 이게 나아야 한다

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        assertThat(recommendation.getDurationMin()).isEqualTo(20);
        assertThat(recommendation.getRoute().isSelected()).isTrue();
        assertThat(recommendation.getRoute().getRankNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("후보가 여럿이면 전부 저장하고 순위를 매긴다 — 2·3순위도 화면에 보여야 한다")
    void savesAllCandidatesWithRank() {
        givenCandidates(
                candidate(0, 3000, busStep(1, 3000, EDGE_1)),
                candidate(1, 1200, busStep(1, 1200, EDGE_2)));

        recommendationService.recommend(owner.getId(), schedule.getId(), TriggerType.INITIAL);

        var routes = savedRouteRepository.findAllByScheduleIdOrderByRankNoAsc(schedule.getId());
        assertThat(routes).hasSize(2);
        assertThat(routes).extracting(route -> route.getRankNo()).containsExactly(1, 2);
        assertThat(routes.stream().filter(route -> route.isSelected()).count())
                .as("주 경로는 하나뿐이어야 한다")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 저장 모양
    // ------------------------------------------------------------------

    @Test
    @DisplayName("구간과 edge 가 계산에 쓴 것과 같은 모양으로 저장된다")
    void persistsSegmentsAndEdges() {
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1, EDGE_2)));

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        List<String> edgeIds = inTransaction(() -> savedRouteRepository
                .findById(recommendation.getRoute().getId()).orElseThrow()
                .getSegments().get(0).getEdges().stream()
                .map(edge -> edge.getSegmentId()).toList());

        assertThat(edgeIds).containsExactly(EDGE_1, EDGE_2);
    }

    @Test
    @DisplayName("매핑이 EXACT 가 아니면 edge 를 만들지 않는다 — 확신 없는 ID 로 남의 지연을 얹지 않는다")
    void skipsEdgesWhenMappingIsNotExact() {
        RouteSegment unmapped = new RouteSegment(1, TransportMode.BUS, null, "간선 146", null,
                "집앞", "회사앞", 5000, 1800, List.of(), List.of(), List.of(),
                mapping(TransitMappingStatus.PARTIAL, EDGE_1));
        givenCandidates(candidate(0, 1800, unmapped));

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        List<String> edgeIds = inTransaction(() -> savedRouteRepository
                .findById(recommendation.getRoute().getId()).orElseThrow()
                .getSegments().get(0).getEdges().stream()
                .map(edge -> edge.getSegmentId()).toList());

        assertThat(edgeIds).isEmpty();
    }

    @Test
    @DisplayName("재현용 스냅샷에 구간별 출처가 남는다")
    void snapshotRecordsSource() throws Exception {
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1, EDGE_2)));

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        JsonNode snapshot = objectMapper.readTree(recommendation.getSegmentStatusSnapshot());
        assertThat(snapshot.get("segments")).hasSize(2);
        assertThat(snapshot.get("segments").get(0).get("source").asText())
                .isEqualTo("ROUTE_API_SPLIT");
        assertThat(snapshot.get("segments").get(0).get("synthetic").asBoolean())
                .as("배분값이라는 사실이 남아야 학습 표본으로 쓰이는 사고를 막는다")
                .isTrue();
    }

    @Test
    @DisplayName("분배한 edge 시간의 합이 원래 step 시간과 같다 — 저장까지 온 뒤에도")
    void splitSumSurvivesToPersistence() throws Exception {
        givenCandidates(candidate(0, 1801, busStep(1, 1801, EDGE_1, EDGE_2)));

        Recommendation recommendation = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);

        JsonNode snapshot = objectMapper.readTree(recommendation.getSegmentStatusSnapshot());
        long sum = 0;
        for (JsonNode segment : snapshot.get("segments")) {
            sum += segment.get("baseSeconds").asLong();
        }
        assertThat(sum).isEqualTo(1801);
    }

    // ------------------------------------------------------------------
    // 불변 · 소유권
    // ------------------------------------------------------------------

    @Test
    @DisplayName("다시 계산하면 덮어쓰지 않고 새 행이 생긴다 (도메인 규칙 7)")
    void recommendationIsImmutable() {
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1)));

        Recommendation first = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL);
        Recommendation second = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.REFRESH);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(recommendationRepository.count()).isEqualTo(2);
        assertThat(second.getTriggerType()).isEqualTo(TriggerType.REFRESH);
    }

    @Test
    @DisplayName("latest 는 가장 최근 것을 준다")
    void latestReturnsMostRecent() {
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1)));
        recommendationService.recommend(owner.getId(), schedule.getId(), TriggerType.INITIAL);
        Recommendation second = recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.REFRESH);

        Recommendation latest = recommendationService.latest(owner.getId(), schedule.getId());

        assertThat(latest.getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("남의 일정으로는 계산할 수 없다")
    void cannotRecommendOthersSchedule() {
        User stranger = userRepository.save(User.builder()
                .email("stranger@ddubi.test")
                .nickname("남")
                .build());
        givenCandidates(candidate(0, 1800, busStep(1, 1800, EDGE_1)));

        assertThatThrownBy(() -> recommendationService.recommend(
                stranger.getId(), schedule.getId(), TriggerType.INITIAL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
    }

    @Test
    @DisplayName("경로 후보가 없으면 404 — 조용히 빈 답을 주지 않는다")
    void failsWhenNoCandidate() {
        given(routeClient.search(any())).willReturn(
                new RouteSearchResult("KAKAO", Instant.now(), null, List.of()));

        assertThatThrownBy(() -> recommendationService.recommend(
                owner.getId(), schedule.getId(), TriggerType.INITIAL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ROUTE_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 픽스처
    // ------------------------------------------------------------------

    private void givenCandidates(RouteCandidate... candidates) {
        given(routeClient.search(any())).willReturn(
                new RouteSearchResult("KAKAO", Instant.now(), null, List.of(candidates)));
    }

    private static RouteCandidate candidate(int index, long totalSeconds, RouteSegment... segments) {
        return new RouteCandidate(index, RouteType.BUS, 10_000, totalSeconds, 0,
                new FareRange(1500, 1500), 0, 0, List.of(segments));
    }

    private static RouteSegment busStep(int sequence, long durationSeconds, String... segmentIds) {
        return new RouteSegment(sequence, TransportMode.BUS, segmentIds[0], "간선 146", null,
                "집앞", "회사앞", 5000, durationSeconds, List.of(), List.of(), List.of(),
                mapping(TransitMappingStatus.EXACT, segmentIds));
    }

    private static TransitMappingResult mapping(TransitMappingStatus status, String... segmentIds) {
        List<MappedTransitSegment> mapped = new java.util.ArrayList<>();
        for (int i = 0; i < segmentIds.length; i++) {
            mapped.add(new MappedTransitSegment(i + 1, segmentIds[i], TransportMode.BUS,
                    "100100609", null, "146", "상행",
                    "123456", "123789", null, null, 3 + i, 4 + i));
        }
        return new TransitMappingResult(status, "master-2026-09", "policy-v1",
                "100100609", "146", "123456", "123789", 3, 4, "상행",
                1.0, 1.0, 1, null, RealtimeMappingCoverage.FULL,
                status == TransitMappingStatus.EXACT ? mapped : List.of());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
