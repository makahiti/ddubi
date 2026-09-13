package com.ssafy.ddubi.domain.route.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * provider 이동구간을 서울 공식 마스터의 <b>인접 구간들</b>로 전개한 결과
 * ({@code route_segment_edge}).
 *
 * <h3>왜 별도 테이블인가</h3>
 * 카카오가 주는 "step" 하나가 정류장 다섯 개를 지나면, Redis·기준선이 쓰는 canonical
 * 인접구간은 다섯 개다. 그 다섯 개를 각각 알아야 구간별 상태를 얹을 수 있고,
 * 반대로 <b>어떤 구간이 막혔을 때 영향받는 일정을 역조회</b>할 수 있다(F-05).
 *
 * <h3>버전을 함께 남기는 이유</h3>
 * 매핑 결과는 마스터 데이터와 정책에 따라 달라진다. 나중에 "이 경로는 왜 이렇게 잘렸나" 를
 * 물었을 때, 그때 쓴 마스터·정책 버전이 없으면 답할 수 없다.
 *
 * <p>{@code created_at} 만 있고 {@code updated_at} 이 없다 — 이 행은 만들어진 뒤 바뀌지 않는다.
 * 그래서 {@code BaseTimeEntity} 를 상속하지 않는다.
 */
@Entity
@Table(name = "route_segment_edge")
@IdClass(SavedRouteSegmentEdge.Key.class)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedRouteSegmentEdge {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_segment_id", nullable = false)
    private SavedRouteSegment routeSegment;

    /** provider step 안에서의 canonical edge 순서 (1부터). */
    @Id
    @Column(name = "edge_seq", nullable = false)
    private Integer edgeSeq;

    /** Redis·baseline_serving 과 같은 공용 인접구간 ID. */
    @Column(name = "segment_id", nullable = false, length = 180)
    private String segmentId;

    @Column(name = "mapping_version", nullable = false, length = 50)
    private String mappingVersion;

    @Column(name = "policy_version", nullable = false, length = 30)
    private String policyVersion;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private SavedRouteSegmentEdge(Integer edgeSeq, String segmentId,
                                  String mappingVersion, String policyVersion) {
        this.edgeSeq = edgeSeq;
        this.segmentId = segmentId;
        this.mappingVersion = mappingVersion;
        this.policyVersion = policyVersion;
    }

    void assignTo(SavedRouteSegment routeSegment) {
        this.routeSegment = routeSegment;
    }

    /** 복합 기본키 (route_segment_id, edge_seq). */
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long routeSegment;
        private Integer edgeSeq;
    }
}
