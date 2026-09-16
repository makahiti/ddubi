package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.entity.*;
import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.recommendation.repository.RecommendationRepository;
import com.ssafy.ddubi.domain.route.entity.*;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.repository.*;
import com.ssafy.ddubi.domain.schedule.entity.*;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.global.exception.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 작업의 DB 경계. schedule 입력 버전과 lease 토큰을 대조하여 늦게 끝난 계산이 최신 입력을
 * 덮어쓰지 못하게 한다. 외부 경로/Redis 조회는 트랜잭션 밖의 Workflow가 수행한다.
 * route/route_segment/route_segment_edge와 추천 스냅샷은 한 트랜잭션으로 저장한다.
 */
@Service
public class ScheduleEvaluationStore {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ScheduleRepository schedules;
    private final RouteRequestRepository requests;
    private final SavedRouteRepository routes;
    private final RecommendationRepository recommendations;
    private final RouteCandidateAssembler assembler;
    private final ScheduleEvaluationWakeup wakeup;

    public ScheduleEvaluationStore(JdbcTemplate jdbc, ObjectMapper json, ScheduleRepository schedules,
            RouteRequestRepository requests, SavedRouteRepository routes, RecommendationRepository recommendations,
            RouteCandidateAssembler assembler, ScheduleEvaluationWakeup wakeup) {
        this.jdbc = jdbc; this.json = json; this.schedules = schedules; this.requests = requests;
        this.routes = routes; this.recommendations = recommendations; this.assembler = assembler; this.wakeup = wakeup;
    }

    /** 캐시는 후보 생성 시각과 DB 경로 ID를 함께 보존한다. 분 단위 DB 값으로 재계산하지 않는다. */
    public record Cache(RouteSearchResult search, Map<String, Long> routeIds, String selectedKey,
                        RouteCandidate selectedCandidate) { }
    /** lazy 엔티티를 외부 I/O로 넘기지 않는 입력 스냅샷. updatedAt은 기존 일정 수정 계약의 버전이다. */
    public record Job(long scheduleId, long userId, LocalDateTime version, Instant departure, Instant arriveBy,
                      Coordinate origin, Coordinate destination, String token, long eventRevision,
                      Cache cache, String previousJson, Importance importance) { }

    /** 소유권·상태 확인 후 짧게 lease를 잡는다. 프로세스가 죽으면 120초 뒤 다른 작업이 회수한다. */
    @Transactional
    public Job claim(long scheduleId, Long userId, Instant now) {
        lockSchedule(scheduleId);
        Schedule s = owned(scheduleId, userId);
        if (s.getStatus() != ScheduleStatus.UPCOMING || !instant(s.getArriveBy()).isAfter(now))
            throw new BusinessException(ErrorCode.INVALID_STATE, "진행 예정 일정만 평가할 수 있습니다.");
        if (s.getPlannedDepartAt() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "일정의 출발시각을 입력해 주세요.");
        jdbc.update("INSERT IGNORE INTO schedule_evaluation(schedule_id,next_check_at) VALUES (?,?)", scheduleId, ts(now));
        var state = jdbc.queryForMap("SELECT * FROM schedule_evaluation WHERE schedule_id=? FOR UPDATE", scheduleId);
        if (Boolean.TRUE.equals(state.get("stopped"))) throw new BusinessException(ErrorCode.INVALID_STATE, "출발 확인으로 감시가 종료됐습니다.");
        LocalDateTime until = databaseTime(state.get("lease_until"));
        if (until != null && until.isAfter(local(now)))
            throw new BusinessException(ErrorCode.INVALID_STATE, "이 일정은 이미 계산 중입니다.");
        String token = UUID.randomUUID().toString();
        jdbc.update("UPDATE schedule_evaluation SET lease_token=?,lease_until=? WHERE schedule_id=?", token, ts(now.plusSeconds(120)), scheduleId);
        boolean sameInput = Objects.equals(databaseTime(state.get("input_updated_at")), s.getUpdatedAt());
        Cache cache = sameInput ? read((String) state.get("candidates_json"), Cache.class) : null;
        return new Job(scheduleId, s.getUser().getId(), s.getUpdatedAt(), instant(s.getPlannedDepartAt()), instant(s.getArriveBy()),
                new Coordinate(s.getOrigin().getLng().doubleValue(), s.getOrigin().getLat().doubleValue()),
                new Coordinate(s.getDestination().getLng().doubleValue(), s.getDestination().getLat().doubleValue()),
                token, ((Number) state.get("event_revision")).longValue(), cache,
                sameInput ? (String) state.get("result_json") : null, s.getImportance());
    }

    /**
     * 버전·lease 검증 이후에만 후보·추천·알림 대기를 원자 저장한다. 자동 추천은 기존 선택 경로를
     * 바꾸지 않는다. 최초 후보만 기본 선택하고 이후 변경은 사용자 수락 API에서 처리한다.
     */
    @Transactional
    public ScheduleEvaluationWorkflow.Outcome complete(Job job, RouteSearchResult search, boolean refreshed,
            ScheduleEvaluationWorkflow.Outcome outcome, Instant nextAt, String notificationJson, String notificationKey, Instant now) {
        lockSchedule(job.scheduleId());
        Schedule s = owned(job.scheduleId(), job.userId());
        var state = jdbc.queryForMap("SELECT * FROM schedule_evaluation WHERE schedule_id=? FOR UPDATE", job.scheduleId());
        if (!Objects.equals(job.token(), state.get("lease_token")) || !s.getUpdatedAt().equals(job.version())
                || s.getStatus() != ScheduleStatus.UPCOMING || Boolean.TRUE.equals(state.get("stopped"))
                || !instant(s.getArriveBy()).isAfter(now)) {
            release(job, now);
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        }
        Cache cache = job.cache();
        if (refreshed) {
            RouteRequest request = requests.save(RouteRequest.builder().schedule(s).provider(RouteProvider.KAKAO)
                    .requestedAt(local(search.fetchedAt())).rawResponse(null).build());
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
            RouteCandidate selectedCandidate = search.candidates().stream().filter(c -> RouteEvidenceAssembler.key(c).equals(chosen))
                    .findFirst().orElse(job.cache() == null ? null : job.cache().selectedCandidate());
            cache = new Cache(search, ids, chosen, selectedCandidate);
        }
        if (cache == null) throw new IllegalStateException("후보 캐시가 없습니다.");
        var chosenResult = outcome.suggestion().proposed() != null ? outcome.suggestion().proposed() : outcome.selected();
        var persistence = persistence(outcome, chosenResult, cache);
        // Persistence를 먼저 붙인다. reasons 컬럼에 들어갈 JSON에도 검증용 표시가 남아야
        // 나중에 추천 근거만 꺼내 봐도 그 숫자의 출처를 알 수 있다.
        var stored = outcome.withPersistence(persistence);
        Long recommendationId = null;
        if (persistence.saved()) {
            long seconds = Duration.between(chosenResult.departureAt(), chosenResult.estimatedArrivalAt()).getSeconds();
            Recommendation recommendation = recommendations.saveAndFlush(Recommendation.builder().schedule(s)
                    .route(routes.getReferenceById(cache.routeIds().get(chosenResult.journeyKey())))
                    .departAt(local(chosenResult.departureAt())).eta(local(chosenResult.estimatedArrivalAt()))
                    .durationMin(Math.toIntExact((seconds + 59) / 60)).bufferMin(0).percentileUsed(null)
                    // 응답에 실은 등급과 행에 남는 등급이 갈라지지 않도록 같은 값을 쓴다.
                    .confidence(persistence.confidence()).reasons(write(stored)).segmentStatusSnapshot(write(outcome.evidence()))
                    .dataCheckedAt(local(outcome.checkedAt())).policyVersion("sched-v1").triggerType(outcome.trigger()).build());
            recommendationId = recommendation.getId();
        }
        var saved = stored.withRecommendationId(recommendationId);
        // 계산 중 들어온 이벤트는 다음 평가를 앞당긴다. 완료가 event-trigger를 지우지 않는다.
        if (((Number) state.get("event_revision")).longValue() != job.eventRevision()) nextAt = now;
        jdbc.update("UPDATE schedule_evaluation SET input_updated_at=?,candidates_json=?,result_json=?,next_check_at=?,"
                        + "lease_token=NULL,lease_until=NULL,last_error=NULL,notification_json=?,notification_key=? WHERE schedule_id=?",
                job.version(), write(cache), write(saved), ts(nextAt), notificationJson, notificationKey, job.scheduleId());
        return saved;
    }

    /** 4xx를 같은 요청으로 즉시 반복하지 않는다. 실패는 별도 상태로 남기고 이전 결과를 최신처럼 표시하지 않는다. */
    @Transactional
    public void fail(Job job, String reason, Instant now) {
        jdbc.update("UPDATE schedule_evaluation SET lease_token=NULL,lease_until=NULL,last_error=?,next_check_at=? WHERE schedule_id=? AND lease_token=?",
                reason, ts(now.plusSeconds(60)), job.scheduleId(), job.token());
    }

    /** 외부 조회가 필요 없는 최근 결과 API. 일정 수정·취소 뒤 예전 결과는 반환하지 않는다. */
    @Transactional(readOnly = true)
    public Map<String, Object> latest(long scheduleId, long userId) {
        Schedule schedule = owned(scheduleId, userId);
        var rows = jdbc.queryForList("SELECT * FROM schedule_evaluation WHERE schedule_id=?", scheduleId);
        var response = new LinkedHashMap<String, Object>();
        if (schedule.getStatus() != ScheduleStatus.UPCOMING || !instant(schedule.getArriveBy()).isAfter(Instant.now())) { response.put("state", "STOPPED"); return response; }
        if (schedule.getPlannedDepartAt() == null) { response.put("state", "DEPARTURE_REQUIRED"); return response; }
        if (!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("stopped"))) { response.put("state", "STOPPED"); return response; }
        if (!rows.isEmpty() && rows.get(0).get("last_error") != null) {
            response.put("state", "FAILED"); response.put("lastError", rows.get(0).get("last_error")); return response;
        }
        if (rows.isEmpty() || !Objects.equals(databaseTime(rows.get(0).get("input_updated_at")), schedule.getUpdatedAt())) { response.put("state", "PENDING"); return response; }
        var row = rows.get(0);
        response.put("state", Boolean.TRUE.equals(row.get("stopped")) ? "STOPPED" : row.get("last_error") != null ? "FAILED"
                : row.get("result_json") == null ? "PENDING"
                : !databaseTime(row.get("next_check_at")).isAfter(local(Instant.now())) ? "STALE" : "READY");
        response.put("lastError", row.get("last_error"));
        response.put("result", read((String) row.get("result_json"), ScheduleEvaluationWorkflow.Outcome.class));
        response.put("nextCheckAt", databaseTime(row.get("next_check_at")).atZone(KST).toOffsetDateTime());
        return response;
    }

    /** 입력 수정과 출발/완료를 동시에 확인할 수 있도록 bounded 대상만 조회한다. 입력 없는 기존 일정은 건너뛴다. */
    public List<Long> due(Instant now) {
        return jdbc.queryForList("SELECT s.id FROM schedule s LEFT JOIN schedule_evaluation e ON e.schedule_id=s.id "
                + "WHERE s.status='UPCOMING' AND s.planned_depart_at IS NOT NULL AND s.arrive_by>? "
                + "AND (e.stopped IS NULL OR e.stopped=false) AND (e.lease_until IS NULL OR e.lease_until<=?) "
                + "AND (e.schedule_id IS NULL OR (e.last_error IS NULL AND (e.input_updated_at IS NULL OR e.input_updated_at<>s.updated_at)) OR e.next_check_at<=?) "
                + "ORDER BY COALESCE(e.next_check_at,s.created_at),s.id LIMIT 20", Long.class, ts(now), ts(now), ts(now));
    }

    /**
     * 영향 구간의 대상만 검색하고 일정마다 별도 트랜잭션으로 깨운다. 여기에는 바깥 트랜잭션을
     * 열지 않는다. 부분 성공 후 재전송은 허용하며 입력 버전/lease 검증은 complete에서 유지한다.
     */
    public void wakeSegments(List<String> ids, Instant now) {
        for (String id : new TreeSet<>(ids)) {
            var targets = jdbc.queryForList("SELECT DISTINCT e.schedule_id FROM schedule_evaluation e "
                    + "JOIN schedule s ON s.id=e.schedule_id JOIN route r ON r.schedule_id=s.id "
                    + "JOIN route_segment rs ON rs.route_id=r.id JOIN route_segment_edge edge ON edge.route_segment_id=rs.id "
                    + "WHERE s.status='UPCOMING' AND e.stopped=false AND s.arrive_by>? "
                    + "AND r.is_selected=true AND edge.segment_id=? ORDER BY e.schedule_id", Long.class, ts(now), id);
            for (long scheduleId : targets) wakeup.wake(scheduleId, id, now);
        }
    }

    /** 실제 출발 확인은 추측하지 않는다. 사용자가 명시적으로 확인할 때만 감시를 중단한다. */
    @Transactional
    public void stop(long scheduleId, long userId) {
        lockSchedule(scheduleId); owned(scheduleId, userId);
        jdbc.update("INSERT IGNORE INTO schedule_evaluation(schedule_id,next_check_at) VALUES (?,?)", scheduleId, ts(Instant.now()));
        jdbc.update("UPDATE schedule_evaluation SET stopped=true,lease_token=NULL,lease_until=NULL,notification_json=NULL WHERE schedule_id=?", scheduleId);
    }

    /** 불변 평가 ID와 입력 버전이 같은 제안만 수락한다. 사용자 클릭이 있어야 출발시각과 선택 경로가 바뀐다. */
    @Transactional
    public void accept(long scheduleId, long userId, String evaluationId, Instant now) {
        lockSchedule(scheduleId); Schedule s = owned(scheduleId, userId);
        var pending = jdbc.queryForList("SELECT * FROM schedule_evaluation WHERE schedule_id=? FOR UPDATE", scheduleId);
        if (pending.isEmpty()) throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        var row = pending.get(0);
        var result = read((String) row.get("result_json"), ScheduleEvaluationWorkflow.Outcome.class);
        // 저장(기록)은 허용해도 수락(사용자 출발시각 실제 변경)은 막는다. 모델이 승인되기 전까지
        // 검증 안 된 숫자로 사용자를 실제로 움직이게 하지 않는다. complete()의 판정과 다른 것이 의도다.
        if (result != null && result.preview() != null)
            throw new BusinessException(ErrorCode.INVALID_STATE, "검증용 ML 결과는 일정에 적용할 수 없습니다.");
        if (result == null || !result.evaluationId().equals(evaluationId) || result.suggestion().proposed() == null
                || !Objects.equals(databaseTime(row.get("input_updated_at")), s.getUpdatedAt())
                || row.get("last_error") != null || Boolean.TRUE.equals(row.get("stopped")) || s.getStatus() != ScheduleStatus.UPCOMING
                || ((Number) row.get("event_revision")).longValue() != result.eventRevision()
                || result.suggestion().proposed().departureAt().isBefore(now) || result.checkedAt().plusSeconds(60).isBefore(now))
            throw new BusinessException(ErrorCode.STALE_RECOMMENDATION);
        var proposed = result.suggestion().proposed();
        Cache cache = read((String) row.get("candidates_json"), Cache.class);
        jdbc.update("UPDATE route SET is_selected=false WHERE schedule_id=?", scheduleId);
        jdbc.update("UPDATE route SET is_selected=true WHERE id=? AND schedule_id=?", cache.routeIds().get(proposed.journeyKey()), scheduleId);
        s.changePlannedDepartAt(local(proposed.departureAt())); s.markCustomizedIfRepeatInstance();
        schedules.flush();
        // 새로운 입력으로 다음 평가를 요구한다. 이전 실행은 lease가 사라져 결과를 저장하지 못한다.
        RouteCandidate selectedCandidate = cache.search().candidates().stream()
                .filter(c -> RouteEvidenceAssembler.key(c).equals(proposed.journeyKey())).findFirst().orElseThrow();
        jdbc.update("UPDATE schedule_evaluation SET candidates_json=?,input_updated_at=?,result_json=NULL,next_check_at=?,lease_token=NULL,lease_until=NULL,notification_json=NULL WHERE schedule_id=?",
                write(new Cache(cache.search(), cache.routeIds(), proposed.journeyKey(), selectedCandidate)),
                s.getUpdatedAt(), ts(now), scheduleId);
    }

    /**
     * 추천 행을 남길지 판정하고, 남기지 않으면 사유를 붙인다.
     *
     * <h3>PREVIEW라는 이유로 막지 않는다</h3>
     * 예측 서비스가 미승인이라 지금은 <b>모든</b> 결과가 PREVIEW다. 그걸 저장 조건으로 두면
     * 추천 행이 하나도 안 생기고 최신 추천·추천 근거 API가 계속 빈손이다(2026-09-14 합의).
     * 검증용이라는 사실은 지우지 않고 {@code confidence=LOW}와 Persistence로 행에 함께 남긴다.
     * <b>수락({@link #accept})과 자동 알림은 여전히 막혀 있다</b> — 저장은 기록일 뿐이지만
     * 그 둘은 사용자의 출발시각을 바꾸고 폰을 울린다.
     *
     * <h3>저장하지 않을 때 왜 사유가 필요한가</h3>
     * 추천이 비어 있는 화면에서 "아직 계산 중"과 "계산은 했는데 근거가 없다"는 전혀 다른
     * 상황인데, recommendationId가 null이라는 사실만으로는 둘을 구분할 수 없다.
     */
    private ScheduleEvaluationWorkflow.Persistence persistence(ScheduleEvaluationWorkflow.Outcome outcome,
            RouteTimelineEvaluator.Result chosen, Cache cache) {
        MlRoutePreview.Comparison preview = outcome.preview();
        MlRoutePreview.Candidate candidate = chosen == null || preview == null ? null
                : preview.candidates().stream().filter(c -> c.result().journeyKey().equals(chosen.journeyKey()))
                        .findFirst().orElse(null);
        // 저장하지 못하는 세 가지. ROUTE_NOT_STORED는 캐시에 없는 후보를 고른 경우로,
        // 조용히 NPE로 죽는 대신 사유로 드러낸다.
        String reason = chosen == null ? "NO_CANDIDATE"
                : chosen.estimatedArrivalAt() == null ? "ETA_UNAVAILABLE"
                : cache.routeIds().get(chosen.journeyKey()) == null ? "ROUTE_NOT_STORED" : null;
        // 미승인 모델로 계산한 값이라 등급을 매기지 않는다. 규약 4장 규칙 8의 HIGH/MID/LOW가
        // 실제로 갈리는 것은 예측 서비스와 baseline_serving이 붙은 뒤다.
        return new ScheduleEvaluationWorkflow.Persistence(reason == null, reason, preview != null,
                preview == null ? null : preview.status(), preview != null && preview.productionReady(),
                Confidence.LOW,
                candidate == null ? null : candidate.quality(), candidate != null && candidate.apiSteps() > 0,
                candidate == null ? 0 : candidate.mlSteps(), candidate == null ? 0 : candidate.apiSteps(),
                candidate == null ? 0 : candidate.subwaySteps(), candidate == null ? 0 : candidate.busWaitSteps(),
                chosen == null ? List.of() : chosen.reasons());
    }

    Schedule owned(long id, Long userId) {
        Schedule s = schedules.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (userId != null && !s.isOwnedBy(userId)) throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        return s;
    }
    private void lockSchedule(long id) { jdbc.queryForList("SELECT id FROM schedule WHERE id=? FOR UPDATE", id); }
    private void release(Job job, Instant now) { jdbc.update("UPDATE schedule_evaluation SET lease_token=NULL,lease_until=NULL,next_check_at=? WHERE schedule_id=? AND lease_token=?", ts(now), job.scheduleId(), job.token()); }
    public String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("평가 직렬화 실패"); } }
    public <T> T read(String value, Class<T> type) { if (value == null) return null; try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("저장 평가 형식 불일치"); } }
    /**
     * MySQL DATETIME의 JDBC getObject 결과를 KST 업무시각으로 통일한다. Connector/J는
     * LocalDateTime을 반환할 수 있고 다른 JDBC 경로는 Timestamp를 반환한다. 시간대를 재해석하거나
     * 정밀도를 잘라 입력 버전을 바꾸지 않는다. null은 미평가 상태이고 알 수 없는 타입은 실패시킨다.
     */
    static LocalDateTime databaseTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof Timestamp time) return time.toLocalDateTime();
        throw new IllegalStateException("평가 DB 시각 타입을 확인할 수 없습니다.");
    }
    static Instant instant(LocalDateTime value) { return value.atZone(KST).toInstant(); }
    static LocalDateTime local(Instant value) { return LocalDateTime.ofInstant(value, KST); }
    /**
     * DATETIME은 순간이 아닌 KST 업무시각이므로 JDBC 4.2 LocalDateTime으로 바인딩한다.
     * Timestamp를 거치면 JVM이 UTC인 CI에서 Connector/J의 연결 시간대(+09:00) 변환이
     * 다시 적용되어 입력 버전과 다음 평가시각이 밀린다. 나노초를 임의로 잘라 비교를 완화하지 않는다.
     */
    static LocalDateTime ts(Instant value) { return local(value); }
}
