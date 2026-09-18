package com.ssafy.ddubi.domain.pipeline.controller;

import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.recommendation.evaluation.BusHistoricalReferenceService;
import com.ssafy.ddubi.global.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 기존 /internal/** API 키 필터와 인프라 IP 제한을 사용한다. 활성화 API는 의도적으로 없다. */
@RestController
@RequestMapping("/internal/bus-references/{version}")
public class InternalBusReferenceController {
    private final BusReferenceService service;
    private final BusHistoricalReferenceService historical;
    public InternalBusReferenceController(BusReferenceService service,BusHistoricalReferenceService historical) {
        this.service=service;this.historical=historical;
    }
    @GetMapping("/status")
    public ApiResponse<Map<String,Object>> status(@PathVariable String version) {
        return ApiResponse.success(service.status(version));
    }
    /** 읽기 전용: 과거 평균 fallback 설정과 마지막 gate 결과·사유. 대용량 COUNT를 다시 실행하지 않는다. */
    @GetMapping("/gate")
    public ApiResponse<Map<String,Object>> gate(@PathVariable String version) {
        var out=new LinkedHashMap<String,Object>(historical.gateStatus());
        out.put("requestedVersion",version);
        out.put("matchesConfiguredVersion",version.equals(out.get("dataVersion")));
        return ApiResponse.success(out);
    }
    @PostMapping("/chunks/{chunk}")
    public ApiResponse<Map<String,Object>> upload(@PathVariable String version,@PathVariable int chunk,
            @RequestBody BusReferenceService.Upload request) {
        return ApiResponse.success(service.upload(version,chunk,request));
    }
    @PostMapping("/lookup")
    public ApiResponse<Map<String,Object>> lookup(@PathVariable String version,@RequestBody BusReferenceService.Query query) {
        return ApiResponse.success(service.lookup(version,query));
    }
}
