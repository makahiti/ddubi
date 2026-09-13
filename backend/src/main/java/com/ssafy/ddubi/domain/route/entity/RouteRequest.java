package com.ssafy.ddubi.domain.route.entity;

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
 * 외부 길찾기 API 호출 1회.
 *
 * <h3>원본을 통째로 남기는 이유</h3>
 * 추천은 불변 스냅샷이라 "그때 왜 그 답이 나왔나" 를 나중에 되짚어야 한다. 우리가 뽑아 쓴
 * 필드만 남기면 <b>안 뽑은 필드 때문에 생긴 문제</b>는 영영 재현하지 못한다.
 *
 * <p>{@code raw_response} 는 DDL 주석대로 <b>응답·로그에 노출하지 않는다.</b>
 * 좌표와 경유지가 그대로 들어 있다.
 */
@Entity
@Table(name = "route_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private RouteProvider provider;

    @Lob
    @Column(name = "raw_response", columnDefinition = "json")
    private String rawResponse;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Builder
    private RouteRequest(Schedule schedule, RouteProvider provider,
                         String rawResponse, LocalDateTime requestedAt) {
        this.schedule = schedule;
        this.provider = provider;
        this.rawResponse = rawResponse;
        this.requestedAt = requestedAt;
    }
}
