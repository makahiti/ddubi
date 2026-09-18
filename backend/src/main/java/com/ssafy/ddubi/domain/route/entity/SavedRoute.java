package com.ssafy.ddubi.domain.route.entity;

import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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
 * 후보 경로 ({@code route} 테이블).
 *
 * <h3>이름이 왜 SavedRoute 인가</h3>
 * 같은 도메인에 외부 응답을 담는 {@code route.model} 패키지가 이미 있다. 거기 이름들과
 * 겹치면 임포트 한 줄 잘못 골라도 컴파일이 되는 상황이 생긴다 — <b>DB 에 저장된 것</b>과
 * <b>외부에서 받아 온 것</b>은 수명이 다르므로 이름으로 구분한다.
 *
 * <h3>rank_no 는 캐시다</h3>
 * DDL 주석대로 "최신 순위 캐시" 이고 과거 순위의 진실은 {@code recommendation} 에 있다.
 * 재계산하면 순위가 바뀌는데, 그때 지난 추천의 근거까지 같이 바뀌면 안 된다.
 */
@Entity
@Table(name = "route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedRoute extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_request_id", nullable = false)
    private RouteRequest routeRequest;

    @Column(name = "provider_path_id", length = 100)
    private String providerPathId;

    /** 외부 API 기본 예상시간(분) — 뚜비 보정 전. */
    @Column(name = "base_duration_min", nullable = false)
    private Integer baseDurationMin;

    @Column(name = "is_selected", nullable = false)
    private boolean selected;

    @Column(name = "rank_no")
    private Integer rankNo;

    /**
     * 구간은 경로와 생명주기를 같이한다. 경로를 지우면 구간도 의미가 없다.
     * DDL 도 {@code ON DELETE CASCADE} 다.
     */
    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq asc")
    private List<SavedRouteSegment> segments = new ArrayList<>();

    @Builder
    private SavedRoute(Schedule schedule, RouteRequest routeRequest, String providerPathId,
                       Integer baseDurationMin, boolean selected, Integer rankNo) {
        this.schedule = schedule;
        this.routeRequest = routeRequest;
        this.providerPathId = providerPathId;
        this.baseDurationMin = baseDurationMin;
        this.selected = selected;
        this.rankNo = rankNo;
    }

    public void addSegment(SavedRouteSegment segment) {
        segments.add(segment);
        segment.assignTo(this);
    }

    /** 사용자가 고른 주 경로로 표시한다. 재계산·알림 대상이 된다. */
    public void select() {
        this.selected = true;
    }

    public void unselect() {
        this.selected = false;
    }

    public void updateRank(Integer rankNo) {
        this.rankNo = rankNo;
    }
}
