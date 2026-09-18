package com.ssafy.ddubi.domain.route.controller;

import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.service.PublicBusReferenceService;
import com.ssafy.ddubi.domain.route.service.TransitReferencePackageService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.exception.*;
import com.ssafy.ddubi.global.security.LoginUserId;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/routes")
public class TransitReferenceController {
    private final PublicBusReferenceService bus;
    private final TransitReferencePackageService packages;
    public TransitReferenceController(PublicBusReferenceService bus,TransitReferencePackageService packages) {this.bus=bus;this.packages=packages;}
    @PostMapping("/bus-references/{dataVersion}/lookup")
    public ApiResponse<Map<String,Object>> lookup(@LoginUserId Long userId,@PathVariable String dataVersion,
            @RequestBody BusReferenceService.Query query) {
        if(userId==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return ApiResponse.success(bus.lookup(dataVersion,query));
    }
    @GetMapping("/reference-packages/{kind}/{referenceId}")
    public ApiResponse<Map<String,Object>> detail(@LoginUserId Long userId,@PathVariable String kind,@PathVariable String referenceId) {
        if(userId==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return ApiResponse.success(packages.lookup(kind,referenceId));
    }
}
