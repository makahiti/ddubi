package com.ssafy.ddubi.domain.recommendation.controller;

import com.ssafy.ddubi.domain.recommendation.service.RoadClosureDemoRunner;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 첫 결과를 그린 클라이언트가 호출하는 발표용 진입점이다. 기본 설정에서는 Bean 자체가 없다. 소유권·시연 제목·서울 좌표·선택 경로/대안 검증은 support에서 서버
 * 저장값으로 확인한다. 임의 도로 링크나 알림 대상 사용자 ID는 요청에서 받지 않는다.
 *
 * <p>2026-09-25 부터 서버도 첫 계산 10초 뒤 스스로 시작한다({@code RoadClosureDemoAutoTrigger}). 상세 화면을 열지 않아도 알림이
 * 오게 하려는 것이다. 이 진입점은 화면을 열어 둔 경우 결과를 바로 다시 읽기 위해 남긴다. 이미 걸린 통제면 ALREADY_APPLIED 를 돌려준다.
 */
@RestController
@RequestMapping("/api/v1/dev/road-closure-demo")
@ConditionalOnProperty(name = "ddubi.demo.road-closure.enabled", havingValue = "true")
public class RoadClosureDemoController {
    private final RoadClosureDemoRunner runner;

    public RoadClosureDemoController(RoadClosureDemoRunner runner) {
        this.runner = runner;
    }

    /** Redis 기록 직후 캐시 후보를 재평가하고, 우회 경로가 실제로 나온 경우에만 본인에게 알린다. */
    @PostMapping("/schedules/{scheduleId}/activate")
    public ApiResponse<Map<String, Object>> activate(
            @LoginUserId Long userId, @PathVariable long scheduleId) {
        return ApiResponse.success(runner.run(scheduleId, userId, Instant.now()));
    }
}
