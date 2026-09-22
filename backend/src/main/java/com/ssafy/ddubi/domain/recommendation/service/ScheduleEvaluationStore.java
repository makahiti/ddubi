package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.entity.*;
import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.recommendation.repository.RecommendationRepository;
import com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService;
import com.ssafy.ddubi.domain.route.entity.*;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.repository.*;
import com.ssafy.ddubi.domain.schedule.entity.*;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.global.exception.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 작업의 DB 경계. schedule 입력 버전과 lease 토큰을 대조하여 늦게 끝난 계산이 최신 입력을 덮어쓰지 못하게 한다. 외부 경로/Redis 조회는 트랜잭션
 * 밖의 Workflow가 수행한다. route/route_segment/route_segment_edge와 추천 스냅샷은 한 트랜잭션으로 저장한다.
 */
@Service
public class ScheduleEvaluationStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleEvaluationStore.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ScheduleRepository schedules;
    private final RouteRequestRepository requests;
    private final SavedRouteRepository routes;
    private final RecommendationRepository recommendations;
    private final RouteCandidateAssembler assembler;
    private final ScheduleEvaluationWakeup wakeup;

    public ScheduleEvaluationStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            ScheduleRepository schedules,
            RouteRequestRepository requests,
            SavedRouteRepository routes,
            RecommendationRepository recommendations,
            RouteCandidateAssembler assembler,
            ScheduleEvaluationWakeup wakeup) {
        this.jdbc = jdbc;
        this.json = json;
        this.schedules = schedules;
        this.requests = requests;
        this.routes = routes;
        this.recommendations = recommendations;
        this.assembler = assembler;
        this.wakeup = wakeup;
    }

    /**
     * 일정 입력 revision 동안 재사용할 경로 스냅샷이다. 대중교통 후보와 도보 보강 근거를 함께 보존하여 자동 재평가가 외부 경로·도보 API를 다시 호출하지 않게
     * 한다. 일정 좌표·시각 변경은 input revision을 올려 캐시 전체를 폐기하며, 실시간 지연·통제만 저장 후보 위에서 다시 계산한다.
     */
    public record Cache(
            RouteSearchResult search,
            Map<String, Long> routeIds,
            String selectedKey,
            RouteCandidate selectedCandidate,
            WalkingEnrichmentService.Evidence walkingEvidence) {
        /** 구형 저장 JSON과 단위 테스트 fixture는 도보 근거가 없으므로 다음 평가에서 한 번 보강한다. */
        public Cache(
                RouteSearchResult search,
                Map<String, Long> routeIds,
                String selectedKey,
                RouteCandidate selectedCandidate) {
            this(search, routeIds, selectedKey, selectedCandidate, null);
        }
    }

    /** lazy 엔티티를 외부 I/O로 넘기지 않는 입력 스냅샷. updatedAt은 기존 일정 수정 계약의 버전이다. */
    public record Job(
            long scheduleId,
            long userId,
            LocalDateTime version,
            Instant departure,
            Instant arriveBy,
            Coordinate origin,
            Coordinate destination,
            String token,
            long eventRevision,
            Cache cache,
            String previousJson,
            Importance importance,
            long inputRevision) {}

    /** 소유권·상태 확인 후 짧게 lease를 잡는다. 프로세스가 죽으면 120초 뒤 다른 작업이 회수한다. */
    @Transactional
    public Job claim(long scheduleId, Long userId, Instant now) {
        lockSchedule(scheduleId);
        Schedule s = owned(scheduleId, userId);
        if (!"NOT_STARTED".equals(s.getJourneyState())
                || s.getStatus() != ScheduleStatus.UPCOMING
                || !instant(s.getArriveBy()).isAfter(now))
            throw new BusinessException(ErrorCode.INVALID_STATE, "진행 예정 일정만 평가할 수 있습니다.");
        if (s.getPlannedDepartAt() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "일정의 출발시각을 입력해 주세요.");
        jdbc.update(
                "INSERT IGNORE INTO schedule_evaluation(schedule_id,next_check_at) VALUES (?,?)",
                scheduleId,
                ts(now));
        var state =
                jdbc.queryForMap(
                        "SELECT * FROM schedule_evaluation WHERE schedule_id=? FOR UPDATE",
                        scheduleId);
        if (Boolean.TRUE.equals(state.get("stopped")))
            throw new BusinessException(ErrorCode.INVALID_STATE, "출발 확인으로 감시가 종료됐습니다.");
        LocalDateTime until = databaseTime(state.get("lease_until"));
        if (until != null && until.isAfter(local(now)))
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 일정은 이미 계산 중입니다.");
        String token = UUID.randomUUID().toString();
        jdbc.update(
                "UPDATE schedule_evaluation SET lease_token=?,lease_until=? WHERE schedule_id=?",
                token,
                ts(now.plusSeconds(120)),
                scheduleId);
        boolean sameInput =
                state.get("input_revision") instanceof Number n
                        && n.longValue() == s.getInputRevision();
        Cache cache = sameInput ? read((String) state.get("candidates_json"), Cache.class) : null;
        return new Job(
                scheduleId,
                s.getUser().getId(),
                s.getUpdatedAt(),
                instant(s.getPlannedDepartAt()),
                instant(s.getArriveBy()),
                new Coordinate(
                        s.getOrigin().getLng().doubleValue(), s.getOrigin().getLat().doubleValue()),
                new Coordinate(
                        s.getDestination().getLng().doubleValue(),
                        s.getDestination().getLat().doubleValue()),
                token,
                ((Number) state.get("event_revision")).longValue(),
                cache,
                sameInput ? (String) state.get("result_json") : null,
                s.getImportance(),
                s.getInputRevision());
    }

    /**
     * 버전·lease 검증 이후에만 후보·추천·알림 대기를 원자 저장한다. 자동 추천은 기존 선택 경로를 바꾸지 않는다. 최초 후보만 기본 선택하고 이후 변경은 사용자 수락
     * API에서 처리한다.
     */
    @Transactional
    public ScheduleEvaluationWorkflow.Outcome complete(
            Job job,
            RouteSearchResult search,
            boolean refreshed,
            ScheduleEvaluationWorkflow.Outcome outcome,
            Instant nextAt,
            String notificationJson,
            String notificationKey,
            Instant now) {
        lockSchedule(job.scheduleId());
        Schedule s = owned(job.scheduleId(), job.userId());
        var state =
                jdbc.queryForMap(
                        "SELECT * FROM schedule_evaluation WHERE schedule_id=? FOR UPDATE",
                        job.scheduleId());
        if (!Objects.equals(job.token(), state.get("lease_token"))
                || !s.getUpdatedAt().equals(job.version())
                || s.getInputRevision() != job.inputRevision()
                || databaseTime(state.get("lease_until")) == null
                || !databaseTime(state.get("lease_until")).isAfter(local(now))
                || ((Number) state.get("event_revision")).longValue() != job.eventRevision()
                || !"NOT_STARTED".equals(s.getJourneyState())
                || s.getStatus() != ScheduleStatus.UPCOMING
                || Boolean.TRUE.equals(state.get("stopped"))
                || !instant(s.getArriveBy()).isAfter(now)) {
            release(job, now);
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        }
        Cache cache = job.cache();
        if (refreshed) {
            RouteRequest request =
                    requests.save(
                            RouteRequest.builder()
                                    .schedule(s)
                                    .provider(RouteProvider.KAKAO)
                                    .requestedAt(local(search.fetchedAt()))
                                    .rawResponse(null)
                                    .build());
            var ids = new LinkedHashMap<String, Long>();
            // 이전 선택과 같은 실제 구간열인지 비교한다. 배열 순번만 같다는 이유로 다른 경로를 선택하지 않는다.
            String chosen = outcome.selectedKey();
            if (!"previous-unavailable".equals(chosen)) {
                jdbc.update("UPDATE route SET is_selected=false WHERE schedule_id=?", s.getId());
            }
            int rank = 1;
            for (RouteCandidate candidate : search.candidates()) {
                SavedRoute route = assembler.assemble(s, request, candidate, rank++).route();
                if (RouteEvidenceAssembler.key(candidate).equals(chosen)) route.select();
                routes.saveAndFlush(route);
                ids.put(RouteEvidenceAssembler.key(candidate), route.getId());
            }
            // 새 검색분을 저장했으니 더 쓰이지 않는 옛 후보는 여기서 정리한다.
            // 지난 추천이 가리키는 경로와 선택된 경로는 남기고, 방금 전 검색분은 유예 시간 동안 둔다(레포지토리 조건).
            var stale =
                    routes.findStaleCandidates(
                            s.getId(),
                            request.getId(),
                            local(now).minus(SavedRouteRepository.STALE_CANDIDATE_GRACE));
            if (!stale.isEmpty()) {
                routes.deleteAll(stale);
                log.info("옛 후보 경로 정리 scheduleId={} 삭제={}건", s.getId(), stale.size());
            }
            RouteCandidate selectedCandidate =
                    search.candidates().stream()
                            .filter(c -> RouteEvidenceAssembler.key(c).equals(chosen))
                            .findFirst()
                            .orElse(job.cache() == null ? null : job.cache().selectedCandidate());
            cache =
                    new Cache(
                            search,
                            ids,
                            chosen,
                            selectedCandidate,
                            outcome.conditional() == null ? null : outcome.conditional().walking());
        }
        if (cache == null) throw new IllegalStateException("후보 캐시가 없습니다.");
        var chosenResult =
                outcome.suggestion().proposed() != null
                        ? outcome.suggestion().proposed()
                        : outcome.selected();
        var persistence = persistence(outcome, chosenResult, cache);
        // Persistence를 먼저 붙인다. reasons 컬럼에 들어갈 JSON에도 검증용 표시가 남아야
        // 나중에 추천 근거만 꺼내 봐도 그 숫자의 출처를 알 수 있다.
        var stored = outcome.withPersistence(persistence);
        Long recommendationId = null;
        if (persistence.saved()) {
            long seconds =
                    Duration.between(chosenResult.departureAt(), chosenResult.estimatedArrivalAt())
                            .getSeconds();
            Recommendation recommendation =
                    recommendations.saveAndFlush(
                            Recommendation.builder()
                                    .schedule(s)
                                    .route(
                                            routes.getReferenceById(
                                                    cache.routeIds()
                                                            .get(chosenResult.journeyKey())))
                                    .departAt(local(chosenResult.departureAt()))
                                    .eta(local(chosenResult.estimatedArrivalAt()))
                                    .durationMin(Math.toIntExact((seconds + 59) / 60))
                                    .bufferMin(
                                            outcome.conditional() == null
                                                    ? 0
                                                    : outcome.conditional().appliedBufferSeconds()
                                                            / 60)
                                    .percentileUsed(null)
                                    // 응답에 실은 등급과 행에 남는 등급이 갈라지지 않도록 같은 값을 쓴다.
                                    .confidence(persistence.confidence())
                                    .reasons(write(stored))
                                    .segmentStatusSnapshot(write(outcome.evidence()))
                                    .dataCheckedAt(local(outcome.checkedAt()))
                                    .policyVersion(outcome.policyVersion())
                                    .triggerType(outcome.trigger())
                                    .build());
            recommendationId = recommendation.getId();
        }
        var saved = stored.withRecommendationId(recommendationId);
        // 계산 중 들어온 이벤트는 다음 평가를 앞당긴다. 완료가 event-trigger를 지우지 않는다.
        if (((Number) state.get("event_revision")).longValue() != job.eventRevision()) nextAt = now;
        Instant validUntil =
                ScheduleEvaluationV2.validity(
                        outcome.conditional(), outcome.evidence(), outcome.checkedAt(), nextAt);
        // 실제 적용한 도보/실시간 증거가 정기 주기보다 먼저 만료되면 그때 재평가한다.
        // 이미 만료된 결과는 유효기간을 늘리지 않고 짧은 재시도 간격만 둔다.
        nextAt = ScheduleEvaluationV2.nextRefresh(now, nextAt, validUntil);
        jdbc.update(
                "UPDATE schedule_evaluation SET"
                    + " input_updated_at=?,candidates_json=?,result_json=?,next_check_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL,notification_json=?,notification_key=?"
                    + " WHERE schedule_id=?",
                job.version(),
                write(cache),
                write(saved),
                ts(nextAt),
                notificationJson,
                notificationKey,
                job.scheduleId());
        jdbc.update(
                "UPDATE schedule_evaluation SET"
                    + " input_revision=?,valid_until=?,failure_count=0,failed_input_revision=NULL,retry_exhausted=false"
                    + " WHERE schedule_id=?",
                job.inputRevision(),
                ts(validUntil),
                job.scheduleId());
        // 같은 입력 revision이어도 새 평가가 기존 제안을 대체하면 이전 대기/발송 lease는 무효다.
        jdbc.update(
                "UPDATE evaluation_notification_outbox SET"
                    + " status='CANCELED',lease_token=NULL,lease_until=NULL WHERE schedule_id=? AND"
                    + " status IN ('PENDING','SENDING')",
                job.scheduleId());
        if (notificationJson != null && notificationKey != null) {
            String dedup =
                    java.util.HexFormat.of()
                            .formatHex(
                                    digest(
                                            job.scheduleId()
                                                    + ":"
                                                    + job.inputRevision()
                                                    + ":"
                                                    + job.eventRevision()
                                                    + ":"
                                                    + notificationKey));
            jdbc.update(
                    "INSERT IGNORE INTO"
                        + " evaluation_notification_outbox(schedule_id,evaluation_id,input_revision,event_revision,dedup_key,payload_json,next_attempt_at,valid_until,created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                    job.scheduleId(),
                    job.token(),
                    job.inputRevision(),
                    job.eventRevision(),
                    dedup,
                    notificationJson,
                    ts(now),
                    ts(validUntil),
                    ts(now));
            // 아직 보내지 못한 같은 의미의 알림은 최신 평가로 교체한다. SENT와 소진한 재시도는 되살리지 않는다.
            jdbc.update(
                    "UPDATE evaluation_notification_outbox SET"
                        + " evaluation_id=?,payload_json=?,valid_until=?,status='PENDING' WHERE"
                        + " dedup_key=? AND status='CANCELED' AND attempts<5",
                    job.token(),
                    notificationJson,
                    ts(validUntil),
                    dedup);
            // 새 outbox로 이행한 결과는 legacy dispatcher 대상에서 제거하여 이중 발송을 막는다.
            jdbc.update(
                    "UPDATE schedule_evaluation SET notification_json=NULL,notification_key=NULL"
                        + " WHERE schedule_id=?",
                    job.scheduleId());
        }
        return saved;
    }

    /** 외부 오류는 동일 입력에서 최대 5회, 30~480초 backoff다. 사건/입력 변경은 새 예산을 연다. */
    @Transactional
    public void fail(Job job, String reason, Instant now) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM schedule_evaluation WHERE schedule_id=? AND lease_token=?"
                            + " FOR UPDATE",
                        job.scheduleId(),
                        job.token());
        if (rows.isEmpty()) return;
        var state = rows.get(0);
        boolean stale = "STALE_RECOMMENDATION".equals(reason);
        int attempts =
                stale
                        ? 0
                        : state.get("failed_input_revision") instanceof Number revision
                                        && revision.longValue() == job.inputRevision()
                                ? ((Number) state.get("failure_count")).intValue() + 1
                                : 1;
        Instant next =
                stale ? now : now.plusSeconds(Math.min(480, 30L << Math.min(attempts - 1, 4)));
        jdbc.update(
                "UPDATE schedule_evaluation SET"
                    + " lease_token=NULL,lease_until=NULL,last_error=?,next_check_at=?,failure_count=?,failed_input_revision=?,retry_exhausted=?"
                    + " WHERE schedule_id=? AND lease_token=?",
                reason,
                ts(next),
                attempts,
                job.inputRevision(),
                attempts >= 5,
                job.scheduleId(),
                job.token());
    }

    /** 외부 조회가 필요 없는 최근 결과 API. 일정 수정·취소 뒤 예전 결과는 반환하지 않는다. */
    @Transactional(readOnly = true)
    public Map<String, Object> latest(long scheduleId, long userId) {
        Schedule schedule = owned(scheduleId, userId);
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM schedule_evaluation WHERE schedule_id=?", scheduleId);
        var response = new LinkedHashMap<String, Object>();
        if (!"NOT_STARTED".equals(schedule.getJourneyState())
                || schedule.getStatus() != ScheduleStatus.UPCOMING
                || !instant(schedule.getArriveBy()).isAfter(Instant.now())) {
            return stopped(response, rows);
        }
        if (schedule.getPlannedDepartAt() == null) {
            response.put("state", "DEPARTURE_REQUIRED");
            return response;
        }
        if (!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("stopped"))) {
            return stopped(response, rows);
        }
        if (!rows.isEmpty() && rows.get(0).get("last_error") != null) {
            response.put("state", "FAILED");
            response.put("lastError", rows.get(0).get("last_error"));
            return response;
        }
        if (!rows.isEmpty()
                && databaseTime(rows.get(0).get("lease_until")) != null
                && databaseTime(rows.get(0).get("lease_until")).isAfter(local(Instant.now()))) {
            response.put("state", "RUNNING");
            return response;
        }
        if (rows.isEmpty()
                || !(rows.get(0).get("input_revision") instanceof Number version)
                || version.longValue() != schedule.getInputRevision()) {
            response.put("state", "PENDING");
            return response;
        }
        var row = rows.get(0);
        LocalDateTime lease = databaseTime(row.get("lease_until"));
        if (lease != null && lease.isAfter(local(Instant.now()))) {
            response.put("state", "RUNNING");
            return response;
        }
        response.put(
                "state",
                Boolean.TRUE.equals(row.get("stopped"))
                        ? "STOPPED"
                        : row.get("last_error") != null
                                ? "FAILED"
                                : row.get("result_json") == null
                                        ? "PENDING"
                                        : databaseTime(row.get("valid_until")) == null
                                                        || !databaseTime(row.get("valid_until"))
                                                                .isAfter(local(Instant.now()))
                                                ? "STALE"
                                                : "READY");
        response.put("lastError", row.get("last_error"));
        var outcome =
                read((String) row.get("result_json"), ScheduleEvaluationWorkflow.Outcome.class);
        if (outcome != null
                && ((Number) row.get("event_revision")).longValue() != outcome.eventRevision())
            response.put("state", "STALE");
        response.put("result", outcome);
        response.put(
                "nextCheckAt",
                databaseTime(row.get("next_check_at")).atZone(KST).toOffsetDateTime());
        return response;
    }

    /**
     * 멈춘 일정에도 마지막 계산 결과를 실어 보낸다.
     *
     * <p>예전에는 상태 한 단어("STOPPED")만 돌려줬다. 그러면 앱은 출발 뒤에 "출발 안내가 끝났어요" 밖에 쓸 것이 없고, 출발할 때 봤던 도착 예정·여유·AI
     * 안내가 모두 사라진다(2026-09-22 제보). 마지막 결과는 result_json 에 그대로 남아 있으므로 상태는 STOPPED 로 두고 그 결과를 함께 준다.
     * {@code frozen} 은 이 결과가 더는 갱신되지 않는다는 표시다.
     */
    private Map<String, Object> stopped(
            Map<String, Object> response, List<Map<String, Object>> rows) {
        response.put("state", "STOPPED");
        if (rows.isEmpty()) return response;
        var outcome =
                read(
                        (String) rows.get(0).get("result_json"),
                        ScheduleEvaluationWorkflow.Outcome.class);
        if (outcome == null) return response;
        response.put("result", outcome);
        response.put("frozen", true);
        return response;
    }

    /** 구형 저장 JSON은 v1 조회로 보존한다. v2 계약을 충족하지 않으면 재계산 대기로 표시한다. */
    @Transactional(readOnly = true)
    public Object latestV2(long scheduleId, long userId) {
        var status = latest(scheduleId, userId);
        boolean frozen = Boolean.TRUE.equals(status.get("frozen"));
        if (status.get("result") instanceof ScheduleEvaluationWorkflow.Outcome outcome
                && outcome.conditional() != null) {
            var value = response(outcome, userId, !frozen);
            var map =
                    json.convertValue(
                            value,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    LinkedHashMap<String, Object>>() {});
            map.put("state", status.get("state"));
            if (frozen) map.put("frozen", true);
            return map;
        }
        if (status.containsKey("result")) {
            status.remove("result");
            status.remove("nextCheckAt");
            status.put("state", "PENDING");
        }
        status.put("schemaVersion", "schedule-evaluation-v2");
        return status;
    }

    /**
     * 완료된 결과와 그것이 멈춘 일정의 마지막 결과인지 여부. {@code frozen} 이면 유효기간이 지나도 그대로 써도 된다 — 더는 갱신되지 않는 결과이기 때문이다.
     */
    public record LatestOutcome(ScheduleEvaluationV2 evaluation, boolean frozen) {}

    /**
     * AI 이동 안내처럼 완료된 결과만 문장화하는 기능용. READY 이거나 멈춘 일정의 마지막 결과(frozen)만 돌려준다. PENDING·FAILED·STALE 은
     * 과거 정상 결과처럼 보이면 안 되므로 거절한다.
     *
     * <p>멈춘 일정을 받는 이유: 출발한 뒤에도 사용자는 출발할 때 본 안내를 다시 보고 싶어 한다. 예전에는 READY 만 받아서 출발 직후부터 안내 조회가
     * 거절됐다(2026-09-22 제보).
     */
    @Transactional(readOnly = true)
    public LatestOutcome latestOutcomeV2(long scheduleId, long userId) {
        var status = latest(scheduleId, userId);
        boolean frozen = Boolean.TRUE.equals(status.get("frozen"));
        if (!("READY".equals(status.get("state")) || frozen)
                || !(status.get("result") instanceof ScheduleEvaluationWorkflow.Outcome outcome)
                || outcome.conditional() == null)
            throw new BusinessException(ErrorCode.INVALID_STATE, "최신 추천 계산이 완료된 뒤 이동 안내를 조회해 주세요.");
        return new LatestOutcome(response(outcome, userId, !frozen), frozen);
    }

    /** DB 경로 ID와 입력 원본을 서버 저장값으로만 보충한다. 후보 순번을 DB ID처럼 노출하지 않는다. */
    @Transactional(readOnly = true)
    public ScheduleEvaluationV2 response(ScheduleEvaluationWorkflow.Outcome outcome, long userId) {
        return response(outcome, userId, true);
    }

    /**
     * @param requireCurrentInput 저장된 결과가 지금 입력 버전과 같아야 하는가. 멈춘 일정은 출발 확인이 입력 버전을 올리므로 이 검사를 통과할 수
     *     없다. 그 결과는 더 갱신되지 않는 마지막 결과라 버전이 달라도 그대로 돌려준다.
     */
    @Transactional(readOnly = true)
    public ScheduleEvaluationV2 response(
            ScheduleEvaluationWorkflow.Outcome outcome, long userId, boolean requireCurrentInput) {
        Schedule s = owned(outcome.scheduleId(), userId);
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM schedule_evaluation WHERE schedule_id=?", s.getId());
        var current =
                read((String) row.get("result_json"), ScheduleEvaluationWorkflow.Outcome.class);
        if (current == null || !current.evaluationId().equals(outcome.evaluationId()))
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        if (requireCurrentInput
                && (!(row.get("input_revision") instanceof Number revision)
                        || revision.longValue() != s.getInputRevision()))
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        Cache cache = read((String) row.get("candidates_json"), Cache.class);
        var proposed = outcome.suggestion().proposed();
        return ScheduleEvaluationV2.from(
                outcome.conditional(),
                outcome.evaluationId(),
                s.getId(),
                row.get("input_revision") instanceof Number n ? n.longValue() : null,
                outcome.eventRevision(),
                outcome.trigger().name(),
                s.getUserInputDepartAt() == null ? null : instant(s.getUserInputDepartAt()),
                outcome.plannedDepartAt(),
                outcome.arriveBy(),
                outcome.checkedAt(),
                databaseTime(row.get("valid_until")).atZone(KST).toInstant(),
                databaseTime(row.get("next_check_at")).atZone(KST).toInstant(),
                cache == null ? null : cache.routeIds().get(outcome.selectedKey()),
                cache == null || proposed == null
                        ? null
                        : cache.routeIds().get(proposed.journeyKey()),
                outcome.evidence().reasons(),
                outcome.evidence(),
                cache == null ? List.of() : cache.search().candidates(),
                outcome.selected(),
                cache == null ? Map.of() : cache.routeIds());
    }

    /** 입력 수정과 출발/완료를 동시에 확인할 수 있도록 bounded 대상만 조회한다. 입력 없는 기존 일정은 건너뛴다. */
    public List<Long> due(Instant now) {
        return jdbc.queryForList(
                "SELECT s.id FROM schedule s LEFT JOIN schedule_evaluation e ON e.schedule_id=s.id"
                    + " WHERE s.journey_state='NOT_STARTED' AND s.status='UPCOMING' AND"
                    + " s.planned_depart_at IS NOT NULL AND s.arrive_by>? AND (e.stopped IS NULL OR"
                    + " e.stopped=false) AND (e.lease_until IS NULL OR e.lease_until<=?) AND"
                    + " (e.schedule_id IS NULL OR e.retry_exhausted=false OR"
                    + " e.failed_input_revision<>s.input_revision) AND (e.schedule_id IS NULL OR"
                    + " (e.last_error IS NULL AND (e.input_revision IS NULL OR"
                    + " e.input_revision<>s.input_revision)) OR e.next_check_at<=?) ORDER BY"
                    + " COALESCE(e.next_check_at,s.created_at),s.id LIMIT 20",
                Long.class,
                ts(now),
                ts(now),
                ts(now));
    }

    /**
     * 영향 구간의 대상만 검색하고 일정마다 별도 트랜잭션으로 깨운다. 여기에는 바깥 트랜잭션을 열지 않는다. 부분 성공 후 재전송은 허용하며 입력 버전/lease 검증은
     * complete에서 유지한다.
     */
    public void wakeSegments(List<String> ids, Instant now) {
        for (String id : new TreeSet<>(ids)) {
            var targets =
                    jdbc.queryForList(
                            "SELECT DISTINCT e.schedule_id FROM schedule_evaluation e JOIN schedule"
                                + " s ON s.id=e.schedule_id JOIN route r ON r.schedule_id=s.id JOIN"
                                + " route_segment rs ON rs.route_id=r.id JOIN route_segment_edge"
                                + " edge ON edge.route_segment_id=rs.id WHERE s.status='UPCOMING'"
                                + " AND e.stopped=false AND s.arrive_by>? AND r.is_selected=true"
                                + " AND edge.segment_id=? ORDER BY e.schedule_id",
                            Long.class,
                            ts(now),
                            id);
            for (long scheduleId : targets) wakeup.wake(scheduleId, id, now);
        }
    }

    /** 실제 출발 확인은 추측하지 않는다. 사용자가 명시적으로 확인할 때만 감시를 중단한다. */
    @Transactional
    public void stop(long scheduleId, long userId) {
        lockSchedule(scheduleId);
        Schedule departed = owned(scheduleId, userId);
        departed.depart(local(Instant.now()));
        schedules.flush();
        jdbc.update(
                "UPDATE evaluation_notification_outbox SET"
                    + " status='CANCELED',lease_token=NULL,lease_until=NULL WHERE schedule_id=? AND"
                    + " status IN ('PENDING','SENDING')",
                scheduleId);
        jdbc.update(
                "INSERT IGNORE INTO schedule_evaluation(schedule_id,next_check_at) VALUES (?,?)",
                scheduleId,
                ts(Instant.now()));
        jdbc.update(
                "UPDATE schedule_evaluation SET"
                    + " stopped=true,lease_token=NULL,lease_until=NULL,notification_json=NULL WHERE"
                    + " schedule_id=?",
                scheduleId);
    }

    /** 불변 평가 ID와 입력 버전이 같은 제안만 수락한다. 사용자 클릭이 있어야 출발시각과 선택 경로가 바뀐다. */
    @Transactional
    public void accept(long scheduleId, long userId, String evaluationId, Instant now) {
        lockSchedule(scheduleId);
        Schedule s = owned(scheduleId, userId);
        var pending =
                jdbc.queryForList(
                        "SELECT * FROM schedule_evaluation WHERE schedule_id=? FOR UPDATE",
                        scheduleId);
        if (pending.isEmpty()) throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        var row = pending.get(0);
        if (evaluationId.equals(row.get("accepted_evaluation_id"))) return;
        var result =
                read((String) row.get("result_json"), ScheduleEvaluationWorkflow.Outcome.class);
        // 모델 승인과 사용자 안내는 별개다. 원본 API 예상은 수락할 수 있지만 시험 적용
        // ML 또는 미완료 탐색은 동일한 공개 guidance 정책으로 차단한다.
        if (result != null && !EvaluationGuidance.from(result.conditional()).canAccept())
            throw new BusinessException(
                    ErrorCode.INVALID_STATE, "검증 중이거나 계산이 끝나지 않은 제안은 적용할 수 없습니다.");
        if (result == null
                || !result.evaluationId().equals(evaluationId)
                || result.suggestion().proposed() == null
                || !(row.get("input_revision") instanceof Number rev)
                || rev.longValue() != s.getInputRevision()
                || !"NOT_STARTED".equals(s.getJourneyState())
                || databaseTime(row.get("valid_until")) == null
                || !databaseTime(row.get("valid_until")).isAfter(local(now))
                || row.get("last_error") != null
                || Boolean.TRUE.equals(row.get("stopped"))
                || s.getStatus() != ScheduleStatus.UPCOMING
                || ((Number) row.get("event_revision")).longValue() != result.eventRevision()
                || result.suggestion().proposed().departureAt().isBefore(now))
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        var proposed = result.suggestion().proposed();
        Cache cache = read((String) row.get("candidates_json"), Cache.class);
        jdbc.update("UPDATE route SET is_selected=false WHERE schedule_id=?", scheduleId);
        jdbc.update(
                "UPDATE route SET is_selected=true WHERE id=? AND schedule_id=?",
                cache.routeIds().get(proposed.journeyKey()),
                scheduleId);
        jdbc.update(
                "UPDATE schedule_evaluation SET accepted_evaluation_id=? WHERE schedule_id=?",
                evaluationId,
                scheduleId);
        jdbc.update(
                "UPDATE evaluation_notification_outbox SET status='CANCELED' WHERE schedule_id=?"
                    + " AND status IN ('PENDING','SENDING')",
                scheduleId);
        s.acceptDeparture(local(proposed.departureAt()));
        s.markCustomizedIfRepeatInstance();
        schedules.flush();
        // 새로운 입력으로 다음 평가를 요구한다. 이전 실행은 lease가 사라져 결과를 저장하지 못한다.
        RouteCandidate selectedCandidate =
                cache.search().candidates().stream()
                        .filter(c -> RouteEvidenceAssembler.key(c).equals(proposed.journeyKey()))
                        .findFirst()
                        .orElseThrow();
        jdbc.update(
                "UPDATE schedule_evaluation SET"
                    + " candidates_json=?,input_updated_at=?,input_revision=?,result_json=NULL,next_check_at=?,lease_token=NULL,lease_until=NULL,notification_json=NULL"
                    + " WHERE schedule_id=?",
                write(
                        new Cache(
                                cache.search(),
                                cache.routeIds(),
                                proposed.journeyKey(),
                                selectedCandidate,
                                cache.walkingEvidence())),
                s.getUpdatedAt(),
                s.getInputRevision(),
                ts(now),
                scheduleId);
    }

    /**
     * 추천 행을 남길지 판정하고, 남기지 않으면 사유를 붙인다.
     *
     * <h3>PREVIEW라는 이유로 막지 않는다</h3>
     *
     * 예측 서비스가 미승인이라 지금은 <b>모든</b> 결과가 PREVIEW다. 그걸 저장 조건으로 두면 추천 행이 하나도 안 생기고 최신 추천·추천 근거 API가 계속
     * 빈손이다(2026-09-14 합의). 검증용이라는 사실은 지우지 않고 {@code confidence=LOW}와 Persistence로 행에 함께 남긴다.
     * 수락({@link #accept})은 guidance와 입력 버전·유효기간을 검증하며 기본 API 예상도 허용한다. 자동 알림은 별도 활성화 정책을 따른다. 기록 저장
     * 자체가 모델 운영 승인을 뜻하지 않는다.
     *
     * <h3>저장하지 않을 때 왜 사유가 필요한가</h3>
     *
     * 추천이 비어 있는 화면에서 "아직 계산 중"과 "계산은 했는데 근거가 없다"는 전혀 다른 상황인데, recommendationId가 null이라는 사실만으로는 둘을
     * 구분할 수 없다.
     */
    private ScheduleEvaluationWorkflow.Persistence persistence(
            ScheduleEvaluationWorkflow.Outcome outcome,
            RouteTimelineEvaluator.Result chosen,
            Cache cache) {
        MlRoutePreview.Comparison preview = outcome.preview();
        MlRoutePreview.Candidate candidate =
                chosen == null || preview == null
                        ? null
                        : preview.candidates().stream()
                                .filter(c -> c.result().journeyKey().equals(chosen.journeyKey()))
                                .findFirst()
                                .orElse(null);
        // 저장하지 못하는 세 가지. ROUTE_NOT_STORED는 캐시에 없는 후보를 고른 경우로,
        // 조용히 NPE로 죽는 대신 사유로 드러낸다.
        String reason =
                chosen == null
                        ? "NO_CANDIDATE"
                        : chosen.estimatedArrivalAt() == null
                                ? "ETA_UNAVAILABLE"
                                : cache.routeIds().get(chosen.journeyKey()) == null
                                        ? "ROUTE_NOT_STORED"
                                        : null;
        // 미승인 모델로 계산한 값이라 등급을 매기지 않는다. 규약 4장 규칙 8의 HIGH/MID/LOW가
        // 실제로 갈리는 것은 예측 서비스와 baseline_serving이 붙은 뒤다.
        return new ScheduleEvaluationWorkflow.Persistence(
                reason == null,
                reason,
                preview != null,
                preview == null ? null : preview.status(),
                preview != null && preview.productionReady(),
                Confidence.LOW,
                candidate == null ? null : candidate.quality(),
                candidate != null && candidate.apiSteps() > 0,
                candidate == null ? 0 : candidate.mlSteps(),
                candidate == null ? 0 : candidate.apiSteps(),
                candidate == null ? 0 : candidate.subwaySteps(),
                candidate == null ? 0 : candidate.busWaitSteps(),
                chosen == null ? List.of() : chosen.reasons());
    }

    private byte[] digest(String key) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Schedule owned(long id, Long userId) {
        Schedule s =
                schedules
                        .findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (userId != null && !s.isOwnedBy(userId))
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        return s;
    }

    private void lockSchedule(long id) {
        jdbc.queryForList("SELECT id FROM schedule WHERE id=? FOR UPDATE", id);
    }

    private void release(Job job, Instant now) {
        jdbc.update(
                "UPDATE schedule_evaluation SET lease_token=NULL,lease_until=NULL,next_check_at=?"
                    + " WHERE schedule_id=? AND lease_token=?",
                ts(now),
                job.scheduleId(),
                job.token());
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("평가 직렬화 실패");
        }
    }

    public <T> T read(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("저장 평가 형식 불일치");
        }
    }

    /**
     * MySQL DATETIME의 JDBC getObject 결과를 KST 업무시각으로 통일한다. Connector/J는 LocalDateTime을 반환할 수 있고 다른
     * JDBC 경로는 Timestamp를 반환한다. 시간대를 재해석하거나 정밀도를 잘라 입력 버전을 바꾸지 않는다. null은 미평가 상태이고 알 수 없는 타입은
     * 실패시킨다.
     */
    static LocalDateTime databaseTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof Timestamp time) return time.toLocalDateTime();
        throw new IllegalStateException("평가 DB 시각 타입을 확인할 수 없습니다.");
    }

    static Instant instant(LocalDateTime value) {
        return value.atZone(KST).toInstant();
    }

    static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, KST);
    }

    /**
     * DATETIME은 순간이 아닌 KST 업무시각이므로 JDBC 4.2 LocalDateTime으로 바인딩한다. Timestamp를 거치면 JVM이 UTC인 CI에서
     * Connector/J의 연결 시간대(+09:00) 변환이 다시 적용되어 입력 버전과 다음 평가시각이 밀린다. 나노초를 임의로 잘라 비교를 완화하지 않는다.
     */
    static LocalDateTime ts(Instant value) {
        return local(value);
    }
}
