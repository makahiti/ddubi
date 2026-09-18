package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ssafy.ddubi.domain.route.dto.response.RouteDetailResponse;
import com.ssafy.ddubi.domain.route.dto.response.RouteMapResponse;
import com.ssafy.ddubi.domain.route.dto.response.RouteSummaryResponse;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 저장된 후보 경로를 읽는다. 계산하지 않고 이미 저장된 것만 보여 준다 —
 * 경로를 새로 만드는 것은 추천 평가({@code POST /schedules/{id}/recommendations})의 몫이다.
 *
 * <h3>소유권은 경로가 매달린 일정으로 판단한다</h3>
 * {@code routeId} 는 순번이라 남의 것을 찍어 볼 수 있다. 경로에는 사용자가 직접 달려
 * 있지 않으므로 일정을 거쳐 확인한다. 같은 실수를 알림에서 이미 한 번 했다(2026-09-15 리뷰).
 */
@Service
public class RouteQueryService {

    private static final Logger log = LoggerFactory.getLogger(RouteQueryService.class);
    private static final TypeReference<List<Coordinate>> COORDINATES = new TypeReference<>() {
    };

    private final SavedRouteRepository savedRouteRepository;
    private final ScheduleRepository scheduleRepository;
    private final ObjectReader coordinateReader;

    public RouteQueryService(SavedRouteRepository savedRouteRepository,
                             ScheduleRepository scheduleRepository,
                             ObjectMapper objectMapper) {
        this.savedRouteRepository = savedRouteRepository;
        this.scheduleRepository = scheduleRepository;
        this.coordinateReader = objectMapper.readerFor(COORDINATES)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 일정의 후보 경로 목록. 아직 평가하지 않은 일정은 <b>빈 목록</b>이다.
     *
     * <p>404 가 아닌 이유: 일정은 있고 경로만 아직 없는 상태다. 404 를 주면 화면이
     * "없는 일정" 과 "아직 계산 전" 을 구분할 수 없다.
     */
    @Transactional(readOnly = true)
    public List<RouteSummaryResponse> ofSchedule(Long userId, Long scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return savedRouteRepository.findAllWithSegmentsByScheduleId(scheduleId).stream()
                .map(RouteSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RouteDetailResponse detail(Long userId, Long routeId) {
        return RouteDetailResponse.from(owned(userId, routeId));
    }

    @Transactional(readOnly = true)
    public RouteMapResponse map(Long userId, Long routeId) {
        SavedRoute route = owned(userId, routeId);
        List<RouteMapResponse.Segment> segments = route.getSegments().stream()
                .map(segment -> new RouteMapResponse.Segment(segment.getSeq(), segment.getMode(),
                        segment.getFromName(), segment.getToName(), coordinates(segment)))
                .toList();
        return RouteMapResponse.of(route.getId(), segments);
    }

    private SavedRoute owned(Long userId, Long routeId) {
        SavedRoute route = savedRouteRepository.findWithSegmentsById(routeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        if (!route.getSchedule().isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return route;
    }

    /**
     * 저장된 폴리라인을 좌표 목록으로 되돌린다.
     *
     * <p>읽지 못하면 <b>빈 목록</b>이고 조회는 성공한다. 좌표는 지도를 그리는 부가 정보라,
     * 한 구간이 깨졌다고 경로 안내 전체를 500 으로 막을 이유가 없다. 대신 로그를 남긴다 —
     * 조용히 비면 "왜 선이 안 그려지지" 를 추적할 수 없다.
     *
     * <h3>모르는 필드에 엄격하게 읽는다</h3>
     * 이 프로젝트의 기본 ObjectMapper 는 모르는 필드를 무시한다(Spring Boot 기본값). 그대로
     * 쓰면 <b>모양이 다른 JSON 이 예외 없이 통과</b>한다 — {@code [{"x":1,"y":2}]} 는
     * longitude/latitude 가 없으니 {@code (0.0, 0.0)} 짜리 좌표가 되고, 지도는 기니만
     * 앞바다로 선을 긋는다. 못 읽는 편이 틀린 좌표보다 낫다.
     */
    private List<Coordinate> coordinates(SavedRouteSegment segment) {
        if (segment.getGeometry() == null || segment.getGeometry().isBlank()) {
            return List.of();
        }
        try {
            List<Coordinate> parsed = coordinateReader.readValue(segment.getGeometry());
            if (parsed.stream().anyMatch(RouteQueryService::implausible)) {
                log.warn("구간 좌표가 지구 밖입니다 routeSegmentId={}", segment.getId());
                return List.of();
            }
            return parsed;
        } catch (Exception unreadable) {
            log.warn("구간 좌표를 읽지 못했습니다 routeSegmentId={}", segment.getId(), unreadable);
            return List.of();
        }
    }

    /**
     * WGS84 범위를 벗어났거나 정확히 (0, 0) 인가.
     *
     * <p>(0,0) 을 막는 이유: 좌표 자체로는 유효하지만 대한민국 대중교통 구간에는 나올 수 없고,
     * 필드 이름이 어긋나 기본값 0 이 채워졌을 때 나오는 값이다. 한 점만 섞여도 bounds 가
     * 지구 반대편까지 늘어나 지도 전체가 못 쓰게 된다.
     */
    private static boolean implausible(Coordinate point) {
        return Math.abs(point.latitude()) > 90 || Math.abs(point.longitude()) > 180
                || (point.latitude() == 0 && point.longitude() == 0);
    }
}
