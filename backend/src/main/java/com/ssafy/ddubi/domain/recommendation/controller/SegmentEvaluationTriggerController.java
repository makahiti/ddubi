package com.ssafy.ddubi.domain.recommendation.controller;

import com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * 기존 내부 API 인증으로 보호되는 상태 변화 통지 경계. Spark가 서비스 DB를 쓰지 않고 영향 구간만
 * 알린다. 실제 값은 Redis 계약에서 다시 읽는다. 이벤트 재전송은 due 갱신으로 합쳐진다.
 */
@RestController
@RequestMapping("/internal/recommendations")
public class SegmentEvaluationTriggerController {
    private final ScheduleEvaluationStore store;
    public SegmentEvaluationTriggerController(ScheduleEvaluationStore store) { this.store = store; }
    public record Change(@NotEmpty @Size(max=1000) List<@NotNull @Pattern(regexp="^(BUS|SUB):[A-Za-z0-9._-]{1,50}(:[A-Za-z0-9._-]{1,50}){2}$") String> segmentIds) { }
    @PostMapping("/segment-changes")
    public Map<String,String> changed(@Valid @RequestBody Change request) {
        store.wakeSegments(request.segmentIds(), Instant.now());
        return Map.of("status", "ACCEPTED");
    }
}
