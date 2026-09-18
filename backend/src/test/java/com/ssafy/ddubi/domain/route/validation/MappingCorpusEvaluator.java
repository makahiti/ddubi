package com.ssafy.ddubi.domain.route.validation;

import com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.service.KakaoRouteMapper;
import com.ssafy.ddubi.domain.route.service.MasterDataTransitMappingResolver;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.*;

/**
 * 고정 마스터와 카카오 원문을 운영 Mapper/Resolver에 Replay하는 오프라인 검증 도구다.
 * 테스트 소스에만 두어 서비스 API나 DB에 쓰기 경로를 추가하지 않는다. 외부 API·캐시·현재
 * 시계는 사용하지 않으며, 동일 corpus는 동일 결과를 만든다. 마스터 ID는 정적 canonical ID,
 * 실시간 ID는 별도 필드다. 입력 좌표는 WGS84, 시간은 초와 명시적 UTC offset을 사용한다.
 *
 * <p>알고리즘의 EXACT와 사람이 검수한 정답을 분리한다. 미검수 행을 precision 분모에서
 * 제외하는 대신 검수 범위를 함께 내보내며, SYNTHETIC 결과는 실제 정확도 증거가 아니다.
 * FULL은 대응 ID의 존재만 뜻하고 실제 Redis 관측·최신성을 검증하지 않는다.</p>
 */
public final class MappingCorpusEvaluator {
    private MappingCorpusEvaluator() { }

    /** 과거 schemaVersion=1 corpus의 입력 호환 형식이다. busMaxEndpointDistanceMeters는 읽기만 하며
     * 수도권의 고정 50m 정책을 바꾸지 않는다. 버스 전역 별칭 역시 사용하지 않는다. */
    public record Corpus(int schemaVersion, String kind, String masterDataVersion,
                         String masterSource, double busMaxEndpointDistanceMeters,
                         List<LegacyBusRouteFixture> busRoutes, List<SubwayLineMaster> subwayLines,
                         TransitAliasSnapshot aliases, List<Capture> captures) { }

    /** 실제 응답 한 건. region은 지리 범위이며 운영기관 범위와 다르다. 수집시각 미상은 null이다. */
    public record Capture(String caseId, String region, String capturedAt,
                          KakaoPublicTransitResponse response, List<Label> labels) { }

    /**
     * 수동 정답은 raw routes의 0-based index와 내부 step의 1-based sequence로 지정한다.
     * EXACT 정답은 전체 canonical edge 순서가 필수다. 비EXACT 정답은 빈 edge 목록을 사용한다.
     * labels에서 생략한 BUS/SUBWAY step은 미검수로 남고 WALK에는 정답을 붙이지 않는다.
     */
    public record Label(int candidateIndex, int stepSequence, String reviewedBy, String evidence,
                        TransitMappingStatus expectedStatus, List<String> expectedSegmentIds) { }

    /** 위치·원문 대신 판정과 정답 edge를 출력해 오판의 위치를 특정하는 읽기 전용 결과다. */
    public record Row(String caseId, String region, int candidateIndex, int stepSequence,
                      TransportMode mode, TransitMappingStatus status, String failureReason,
                      String mappingPolicyVersion, RealtimeMappingCoverage realtimeCoverage,
                      List<String> actualSegmentIds, boolean reviewed, Boolean correct,
                      TransitMappingStatus expectedStatus, List<String> expectedSegmentIds) { }

    /** precision은 검수된 EXACT에 한정하며 분모가 0이면 null이다. 모든 비율의 단위는 0~1이다. */
    public record Metrics(int transitSteps, long exactSteps, long reviewedSteps,
                          long reviewedExactSteps, long correctExactSteps, long falseExactSteps,
                          long unreviewedExactSteps, Double reviewedExactPrecision,
                          Double exactCoverage, Double reviewCoverage,
                          long exactWithFullRealtimeIds,
                          Map<String, Long> statusCounts, Map<String, Long> failureCounts) { }

    /** 전체 경로 coverage는 WALK를 제외한 모든 대중교통 step이 EXACT일 때만 증가한다. */
    public record Report(String corpusKind, String masterDataVersion, String masterSource,
                         String evidenceStatus, int missingCaptureTimestampCount, Metrics metrics, int transitCandidates,
                         int allExactCandidates, int allReviewedCorrectCandidates,
                         Map<String, Metrics> byRegionAndMode, List<Row> rows) { }

    /**
     * 1. 입력·정답 참조 검증 → 2. 운영 코드 Replay → 3. 독립 정답 비교·집계 순으로 처리한다.
     * 손상된 응답/중복 case/존재하지 않는 정답 위치는 즉시 실패시켜 선택적 누락을 방지한다.
     * 실제 데이터라는 kind 선언만으로 운영 승인하지 않는다. 이 도구는 통계와 검수 상태만 낸다.
     */
    public static Report evaluate(Corpus corpus) {
        require(corpus != null && corpus.schemaVersion() == 1, "schemaVersion은 1이어야 합니다.");
        require(Set.of("SYNTHETIC", "REAL").contains(Objects.toString(corpus.kind(), "")), "kind 오류");
        requireText(corpus.masterDataVersion(), "masterDataVersion");
        requireText(corpus.masterSource(), "masterSource");
        require(corpus.busRoutes() != null && corpus.subwayLines() != null, "마스터 목록이 필요합니다.");
        require(!corpus.busRoutes().isEmpty() || !corpus.subwayLines().isEmpty(), "빈 마스터는 평가할 수 없습니다.");
        require(corpus.aliases() != null, "별칭 없음도 빈 aliases 객체로 명시해야 합니다.");
        require(corpus.captures() != null && !corpus.captures().isEmpty(), "captures가 필요합니다.");

        // 운영 Resolver의 버전·별칭 fingerprint를 그대로 사용하되 DB 캐시를 주입하지 않는다.
        SeoulTransitMasterDataProvider provider = new SeoulTransitMasterDataProvider() {
            public Optional<String> activeVersion() { return Optional.of(corpus.masterDataVersion()); }
            public List<SubwayLineMaster> loadSubwayLines(String version) { return corpus.subwayLines(); }
            public TransitAliasSnapshot loadAliases(String version) { return corpus.aliases(); }
        };
        KakaoRouteMapper mapper = new KakaoRouteMapper(new MasterDataTransitMappingResolver(
                provider, null, new ProviderSegmentSignatureFactory(), new CanonicalSegmentIdFactory(),
                new MetropolitanBusCatalog(MetropolitanBusFixtures.fromLegacy(corpus.busRoutes(),corpus.masterDataVersion())),
                null, java.time.Clock.fixed(Instant.EPOCH,java.time.ZoneId.of("Asia/Seoul"))));

        List<Row> rows = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        int transitCandidates = 0;
        int allExactCandidates = 0;
        int allReviewedCorrectCandidates = 0;
        int missingCaptureTimestampCount = 0;
        for (Capture capture : corpus.captures()) {
            require(capture != null, "capture는 null일 수 없습니다.");
            requireText(capture.caseId(), "caseId");
            require(caseIds.add(capture.caseId()), "중복 caseId: " + capture.caseId());
            require(Set.of("SEOUL", "OUTSIDE_SEOUL", "MIXED", "UNKNOWN")
                    .contains(Objects.toString(capture.region(), "")), "region 오류");
            // 저장 파일 mtime을 API 수집시각으로 꾸미지 않는다. 미상은 보고서에 남기고,
            // 정적 매핑에만 쓰는 Mapper 호출시각을 epoch로 고정한다. 최신성 평가는 하지 않는다.
            var fetchedAt = capture.capturedAt() == null ? Instant.EPOCH
                    : OffsetDateTime.parse(capture.capturedAt()).toInstant();
            if (capture.capturedAt() == null) { missingCaptureTimestampCount++; }
            Map<String, Label> labels = labelsByPosition(capture.labels());
            for (var candidate : mapper.map(capture.response(), fetchedAt).candidates()) {
                List<Row> candidateRows = new ArrayList<>();
                for (var step : candidate.segments()) {
                    if (step.mode() == TransportMode.WALK) { continue; }
                    var mapping = step.mapping();
                    var actual = mapping.segments().stream().map(MappedTransitSegment::segmentId).toList();
                    Label label = labels.remove(position(candidate.providerCandidateIndex(), step.sequence()));
                    Boolean correct = label == null ? null : mapping.status() == label.expectedStatus()
                            && actual.equals(label.expectedSegmentIds());
                    candidateRows.add(new Row(capture.caseId(), capture.region(), candidate.providerCandidateIndex(),
                            step.sequence(), step.mode(), mapping.status(), mapping.failureReason(),
                            mapping.mappingPolicyVersion(), mapping.realtimeCoverage(), actual, label != null,
                            correct, label == null ? null : label.expectedStatus(),
                            label == null ? null : label.expectedSegmentIds()));
                }
                // 도보 전용 후보를 '모든 대중교통이 정확함'으로 세지 않아 coverage 부풀림을 막는다.
                if (!candidateRows.isEmpty()) {
                    transitCandidates++;
                    if (candidateRows.stream().allMatch(row -> row.status() == TransitMappingStatus.EXACT)) {
                        allExactCandidates++;
                    }
                    if (candidateRows.stream().allMatch(row -> Boolean.TRUE.equals(row.correct()))) {
                        allReviewedCorrectCandidates++;
                    }
                }
                rows.addAll(candidateRows);
            }
            require(labels.isEmpty(), "정답 위치가 없거나 WALK를 가리킵니다: " + capture.caseId());
        }
        require(!rows.isEmpty(), "대중교통 step이 없는 corpus는 평가할 수 없습니다.");

        // 출력 순서를 고정해 모델/마스터 변경 전후 JSON diff와 Replay 비교를 가능하게 한다.
        Map<String, List<Row>> grouped = new TreeMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(row.region() + "/" + row.mode(), key -> new ArrayList<>()).add(row);
        }
        Map<String, Metrics> byRegionAndMode = new TreeMap<>();
        grouped.forEach((key, group) -> byRegionAndMode.put(key, summarize(group)));
        Metrics metrics = summarize(rows);
        String evidenceStatus = corpus.kind().equals("SYNTHETIC") ? "SYNTHETIC_ONLY"
                : metrics.reviewedSteps() < metrics.transitSteps() ? "REVIEW_INCOMPLETE"
                : "REVIEWED_CORPUS_ONLY";
        return new Report(corpus.kind(), corpus.masterDataVersion(), corpus.masterSource(), evidenceStatus, missingCaptureTimestampCount,
                metrics, transitCandidates, allExactCandidates, allReviewedCorrectCandidates, byRegionAndMode,
                List.copyOf(rows));
    }

    private static Map<String, Label> labelsByPosition(List<Label> labels) {
        require(labels != null, "미검수 응답도 labels: []가 필요합니다.");
        Map<String, Label> result = new HashMap<>();
        for (Label label : labels) {
            require(label != null && label.candidateIndex() >= 0 && label.stepSequence() >= 1, "정답 순번 오류");
            requireText(label.reviewedBy(), "reviewedBy");
            requireText(label.evidence(), "evidence");
            require(label.expectedStatus() != null && label.expectedStatus() != TransitMappingStatus.NOT_APPLICABLE,
                    "대중교통 정답 status가 필요합니다.");
            require(label.expectedSegmentIds() != null, "expectedSegmentIds가 필요합니다.");
            require((label.expectedStatus() == TransitMappingStatus.EXACT) == !label.expectedSegmentIds().isEmpty(),
                    "EXACT 정답만 비어 있지 않은 전체 edge 목록을 가져야 합니다.");
            for (String id : label.expectedSegmentIds()) { requireText(id, "expectedSegmentId"); }
            require(result.put(position(label.candidateIndex(), label.stepSequence()), label) == null, "중복 정답 위치");
        }
        return result;
    }

    private static Metrics summarize(List<Row> rows) {
        long exact = 0, reviewed = 0, reviewedExact = 0, correctExact = 0, realtimeFull = 0;
        Map<String, Long> statuses = new TreeMap<>();
        Map<String, Long> failures = new TreeMap<>();
        for (Row row : rows) {
            statuses.merge(row.status().name(), 1L, Long::sum);
            if (row.failureReason() != null && !row.failureReason().equals("NONE")) {
                failures.merge(row.failureReason(), 1L, Long::sum);
            }
            if (row.reviewed()) { reviewed++; }
            if (row.status() == TransitMappingStatus.EXACT) {
                exact++;
                if (row.realtimeCoverage() == RealtimeMappingCoverage.FULL) { realtimeFull++; }
                if (row.reviewed()) {
                    reviewedExact++;
                    if (Boolean.TRUE.equals(row.correct())) { correctExact++; }
                }
            }
        }
        return new Metrics(rows.size(), exact, reviewed, reviewedExact, correctExact, reviewedExact - correctExact,
                exact - reviewedExact, ratio(correctExact, reviewedExact), ratio(exact, rows.size()),
                ratio(reviewed, rows.size()), realtimeFull, statuses, failures);
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static String position(int candidateIndex, int stepSequence) {
        return candidateIndex + ":" + stepSequence;
    }

    private static void requireText(String value, String name) {
        require(value != null && !value.isBlank(), name + "은 비어 있을 수 없습니다.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
