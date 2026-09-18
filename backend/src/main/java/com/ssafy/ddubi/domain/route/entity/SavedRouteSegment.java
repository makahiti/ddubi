package com.ssafy.ddubi.domain.route.entity;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 경로를 이동수단 단위로 자른 구간 ({@code route_segment}).
 *
 * <h3>segment_id 컬럼을 쓰지 않는다</h3>
 * DDL 주석대로 그 열은 <b>레거시 단일-edge 호환</b>이다. 카카오 step 하나가 정류장을 여러 개
 * 지나면 canonical 인접구간도 여러 개가 되는데, 열 하나로는 그중 하나밖에 못 담는다.
 * 신규 저장의 단일 진실은 {@link SavedRouteSegmentEdge} 다.
 */
@Entity
@Table(name = "route_segment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedRouteSegment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private SavedRoute route;

    /** 구간 순서 (1부터). */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private TransportMode mode;

    /** 외부 API 노선·역 식별자 — segment_id 매핑 근거. */
    @Column(name = "provider_route_key", length = 100)
    private String providerRouteKey;

    @Column(name = "from_name", nullable = false, length = 100)
    private String fromName;

    @Column(name = "to_name", nullable = false, length = 100)
    private String toName;

    @Column(name = "base_duration_min", nullable = false)
    private Integer baseDurationMin;

    /** 폴리라인 좌표 배열 — 지도 표시용. */
    @Lob
    @Column(name = "geometry", columnDefinition = "json")
    private String geometry;

    @OneToMany(mappedBy = "routeSegment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("edgeSeq asc")
    private List<SavedRouteSegmentEdge> edges = new ArrayList<>();

    @Builder
    private SavedRouteSegment(Integer seq, TransportMode mode, String providerRouteKey,
                              String fromName, String toName, Integer baseDurationMin,
                              String geometry) {
        this.seq = seq;
        this.mode = mode;
        this.providerRouteKey = providerRouteKey;
        this.fromName = fromName;
        this.toName = toName;
        this.baseDurationMin = baseDurationMin;
        this.geometry = geometry;
    }

    void assignTo(SavedRoute route) {
        this.route = route;
    }

    public void addEdge(SavedRouteSegmentEdge edge) {
        edges.add(edge);
        edge.assignTo(this);
    }
}
