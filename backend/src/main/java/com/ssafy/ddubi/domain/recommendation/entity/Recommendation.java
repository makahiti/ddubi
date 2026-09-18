package com.ssafy.ddubi.domain.recommendation.entity;

import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 추천 결과 스냅샷. <b>불변이다</b> (도메인 규칙 7).
 *
 * <h3>왜 UPDATE 하지 않나</h3>
 * "6시 20분에 출발하라고 했다" 는 사실은 나중에 상황이 바뀌어도 달라지지 않는다. 재계산은
 * 새 행을 만든다. 덮어쓰면 사용자가 본 것과 우리가 가진 기록이 갈라지고, 알림이 왜 갔는지
 * 설명할 수 없게 된다. 그래서 이 클래스에는 <b>세터도 상태 변경 메서드도 없다.</b>
 *
 * <h3>재현에 필요한 것을 함께 저장한다</h3>
 * {@code segment_status_snapshot} 은 계산에 쓴 구간별 상태 원본이고, {@code data_checked_at}
 * 은 그 데이터의 확인 시각이다. 화면의 "18:01 기준" 이 이 값이다. 둘이 없으면 같은 추천을
 * 다시 만들어 볼 수 없다.
 *
 * <h3>percentile_used 에 대하여</h3>
 * 중요도→백분위 정책은 보류 상태다(규약 4장 규칙 7, 2026-09-08). ML 이 p50/p80/p95 를
 * 아직 null 로 주기 때문에, 검증 전의 "p95" 는 이름만 p95 다. 그래서 이 값은 정책의 결과가
 * 아니라 <b>실제로 쓴 값이 무엇이었는지</b>의 기록이다.
 */
@Entity
@Table(name = "recommendation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    /** 당시 1순위 후보. 나중에 순위가 바뀌어도 이 행이 가리키는 경로는 그대로다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private SavedRoute route;

    /** 권장 출발 시각 — 서비스의 최종 답. */
    @Column(name = "depart_at", nullable = false)
    private LocalDateTime departAt;

    @Column(name = "eta", nullable = false)
    private LocalDateTime eta;

    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    /** 기존 DB 호환 필드(분). sched-v1은 별도 여유시간을 더하지 않아 항상 0이다. */
    @Column(name = "buffer_min", nullable = false)
    private Integer bufferMin;

    @Column(name = "percentile_used")
    private Byte percentileUsed;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false)
    private Confidence confidence;

    /** sched-v1의 입력·후보별 타임라인·잠정 여부·제안이 포함된 Outcome JSON. */
    @Lob
    @Column(name = "reasons", columnDefinition = "json")
    private String reasons;

    @Lob
    @Column(name = "segment_status_snapshot", columnDefinition = "json")
    private String segmentStatusSnapshot;

    /** 사용한 교통 데이터의 확인 시각 — 화면의 "18:01 기준" 표시용. */
    @Column(name = "data_checked_at", nullable = false)
    private LocalDateTime dataCheckedAt;

    @Column(name = "policy_version", nullable = false, length = 10)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Builder
    private Recommendation(Schedule schedule, SavedRoute route, LocalDateTime departAt,
                           LocalDateTime eta, Integer durationMin, Integer bufferMin,
                           Byte percentileUsed, Confidence confidence, String reasons,
                           String segmentStatusSnapshot, LocalDateTime dataCheckedAt,
                           String policyVersion, TriggerType triggerType) {
        this.schedule = schedule;
        this.route = route;
        this.departAt = departAt;
        this.eta = eta;
        this.durationMin = durationMin;
        this.bufferMin = bufferMin;
        this.percentileUsed = percentileUsed;
        this.confidence = confidence;
        this.reasons = reasons;
        this.segmentStatusSnapshot = segmentStatusSnapshot;
        this.dataCheckedAt = dataCheckedAt;
        this.policyVersion = policyVersion;
        this.triggerType = triggerType;
    }

    public boolean isOwnedBy(Long userId) {
        return schedule.getUser().getId().equals(userId);
    }
}
