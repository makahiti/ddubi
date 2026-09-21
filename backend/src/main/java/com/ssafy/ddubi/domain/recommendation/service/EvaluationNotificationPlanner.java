package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.service.NotificationCommand;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Assessment;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 평가 결과가 사용자에게 알릴 만한 일인지 판정하고, 알릴 내용을 만든다.
 *
 * <h3>기본값은 꺼짐이다</h3>
 * 지금 나가는 숫자는 <b>미승인 모델</b>이 계산한 것이다(2026-09-15 프론트 확인). 개발·테스트
 * 환경에서만 켜고, 발표 전에는 설정 한 줄로 되돌린다. 코드를 고쳐야 끌 수 있으면 급할 때
 * 못 끈다.
 *
 * <h3>조건부 탐색과 기존 경로 안내</h3>
 * ADVANCE는 재평가로 확인한 출발·도착시각을 EARLY_DEPART에 사용한다. 나머지는 아래 기존 안내를
 * 유지한다. v2 PREVIEW는 shadow 설정을 명시적으로 켠 검증 환경에서만 발송하며 본문에 검증용을 표시한다.
 *
 * <pre>
 * 선택 경로가 LATE_ESTIMATE/BLOCKED   → ON_TIME_UNLIKELY
 * 지금 선택보다 빠른 후보가 있다        → ROUTE_CHANGE
 * 그 외                              → 알리지 않는다
 * </pre>
 *
 * 지각이 더 급한 소식이라 먼저 본다. 둘 다 해당하면 지각을 알리되 대안이 있다는 것도 본문에 적는다.
 */
@Service
public class EvaluationNotificationPlanner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm");

    private final boolean enabled;
    private final Duration meaningfulGain;
    private final boolean shadowPreview;

    public EvaluationNotificationPlanner(
            @Value("${ddubi.recommendation.auto-notify-enabled:false}") boolean enabled,
            @Value("${ddubi.recommendation.notify-route-gain:5m}") Duration meaningfulGain,
            @Value("${ddubi.recommendation.shadow-preview-notify-enabled:false}") boolean shadowPreview) {
        this.enabled = enabled;
        this.meaningfulGain = meaningfulGain; this.shadowPreview=shadowPreview;
    }

    /** 보낼 알림과 그 식별 키. 키가 그대로면 dispatcher 가 다시 보내지 않는다. */
    public record Planned(NotificationCommand command, String key) {
    }

    /**
     * 알릴 일이 있으면 내용을 만든다.
     *
     * <p>{@code recommendationId} 는 비운다. 이 판정은 추천 행을 저장하기 <b>전</b>에 일어난다 —
     * 알림에는 scheduleId 가 있으므로 화면은 최신 추천 조회로 근거를 찾아갈 수 있다.
     */
    public Optional<Planned> plan(ScheduleEvaluationStore.Job job, ScheduleEvaluationWorkflow.Outcome outcome) {
        if (!enabled || outcome.conditional()!=null && !shadowPreview && (outcome.preview()==null || !outcome.preview().productionReady())) {
            return Optional.empty();
        }
        Result selected = outcome.selected();
        Result fastest = outcome.suggestion().proposed();

        if(outcome.conditional()!=null && fastest!=null
                && outcome.conditional().decision()==com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy.Decision.ADVANCE_DEPARTURE
                && Duration.between(fastest.departureAt(),job.departure()).compareTo(meaningfulGain)>=0) {
            String key=shortKey("EARLY_DEPART", fastest.journeyKey()+":"+(fastest.departureAt().getEpochSecond()/300));
            return Optional.of(new Planned(command(job,outcome,NotificationType.EARLY_DEPART,"출발 계획을 확인해 주세요",
                    at(fastest.departureAt())+" 출발 시 "+at(fastest.estimatedArrivalAt())+" 도착 예상입니다.",key),key));
        }
        if (selected != null && late(selected.assessment())) {
            return Optional.of(lateness(job, outcome, selected, fastest));
        }
        if (betterRouteExists(selected, fastest)) {
            return Optional.of(routeChange(job, outcome, selected, fastest));
        }
        return Optional.empty();
    }

    private boolean late(Assessment assessment) {
        return assessment == Assessment.LATE_ESTIMATE || assessment == Assessment.BLOCKED;
    }

    /**
     * 지금 선택보다 <b>의미 있게</b> 빠른 다른 후보가 있는가.
     *
     * <p>1~2분 차이로 알리지 않는다. 경로를 갈아타는 데도 품이 들고, 매 평가마다 후보 순위가
     * 조금씩 흔들리면 알림이 쏟아진다.
     */
    private boolean betterRouteExists(Result selected, Result fastest) {
        if (selected == null || fastest == null || fastest.journeyKey().equals(selected.journeyKey())
                || selected.estimatedArrivalAt() == null || fastest.estimatedArrivalAt() == null) {
            return false;
        }
        return Duration.between(fastest.estimatedArrivalAt(), selected.estimatedArrivalAt())
                .compareTo(meaningfulGain) >= 0;
    }

    private Planned lateness(ScheduleEvaluationStore.Job job, ScheduleEvaluationWorkflow.Outcome outcome,
                             Result selected, Result fastest) {
        boolean hasAlternative = betterRouteExists(selected, fastest);
        String body = selected.estimatedArrivalAt() == null
                ? "지금 경로로는 도착 시각을 계산할 수 없습니다. 앱에서 경로를 다시 확인해 주세요."
                : "예상 도착 " + at(selected.estimatedArrivalAt()) + " · 목표 " + at(job.arriveBy())
                        + (hasAlternative ? ". 더 빠른 경로가 있습니다." : "");
        // 키에 시각을 넣지 않는다. 분마다 1초씩 밀리는 값을 키에 넣으면 상황이 그대로인데도
        // 매 평가가 새 알림이 된다. 경로가 바뀌거나 종류가 바뀔 때만 다시 알린다.
        String key = shortKey("ON_TIME_UNLIKELY", selected.journeyKey());
        return new Planned(command(job, outcome, NotificationType.ON_TIME_UNLIKELY,
                "제시간 도착이 어려울 수 있어요", body, key), key);
    }

    private Planned routeChange(ScheduleEvaluationStore.Job job, ScheduleEvaluationWorkflow.Outcome outcome,
                                Result selected, Result fastest) {
        long gainMinutes = Duration.between(fastest.estimatedArrivalAt(), selected.estimatedArrivalAt()).toMinutes();
        String key = shortKey("ROUTE_CHANGE", selected.journeyKey() + ">" + fastest.journeyKey());
        return new Planned(command(job, outcome, NotificationType.ROUTE_CHANGE,
                "더 빠른 경로가 있어요", gainMinutes + "분 빠른 경로를 찾았습니다. 예상 도착 "
                        + at(fastest.estimatedArrivalAt()) + ".", key),
                key);
    }

    /**
     * 알림 식별 키를 64자 안에 담는다.
     *
     * <p>이 값은 {@code schedule_evaluation.notification_key} 와 {@code notification.suppress_key}
     * 에 그대로 들어가는데 두 칸 모두 {@code varchar(64)} 다. 경로 키(journeyKey)는 53자라
     * ROUTE_CHANGE 처럼 둘을 이어 붙이면 120자가 되어 저장이 실패한다(ER_DATA_TOO_LONG).
     *
     * <p>종류는 읽을 수 있게 남기고 나머지만 고정 길이로 줄인다. 같은 상황이면 같은 값이
     * 나와야 중복 발송을 막을 수 있으므로 해시를 쓴다 — 자르기는 서로 다른 경로가 같은 키로
     * 뭉개질 수 있어 쓰지 않는다.
     */
    static String shortKey(String type, String detail) {
        return type + ":" + sha256Hex(detail).substring(0, 32);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 지원 없음", impossible);
        }
    }

    private NotificationCommand command(ScheduleEvaluationStore.Job job, ScheduleEvaluationWorkflow.Outcome outcome,
                                        NotificationType type, String title, String body, String suppressKey) {
        return NotificationCommand.builder()
                .userId(job.userId())
                .scheduleId(outcome.scheduleId())
                .type(type)
                .title(title)
                .body(outcome.preview()!=null&&!outcome.preview().productionReady()?"[검증용] "+body:body)
                .suppressKey(suppressKey)
                .evaluationId(outcome.evaluationId())
                .decision(outcome.conditional()==null?null:outcome.conditional().decision().name())
                .build();
    }

    private String at(Instant instant) {
        return instant.atZone(KST).toLocalTime().format(AT);
    }
}
