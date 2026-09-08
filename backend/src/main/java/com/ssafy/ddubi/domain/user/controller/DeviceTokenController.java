package com.ssafy.ddubi.domain.user.controller;

import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenDeleteRequest;
import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenRegisterRequest;
import com.ssafy.ddubi.domain.user.dto.response.DeviceTokenResponse;
import com.ssafy.ddubi.domain.user.service.DeviceTokenService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "기기", description = "FCM 푸시 토큰 등록·해제")
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @Operation(summary = "FCM 토큰 등록·갱신",
            description = "fcmToken 기준 upsert. 같은 토큰이 이미 있으면 주인을 현재 사용자로 바꾼다. DEVICE-001")
    @PutMapping("/token")
    public ApiResponse<DeviceTokenResponse> register(
            @LoginUserId Long userId,
            @Valid @RequestBody DeviceTokenRegisterRequest request) {
        return ApiResponse.success(deviceTokenService.register(userId, request));
    }

    @Operation(summary = "FCM 토큰 해제", description = "본인 토큰만 해제할 수 있다. DEVICE-002")
    @DeleteMapping("/token")
    public ResponseEntity<Void> unregister(
            @LoginUserId Long userId,
            @Valid @RequestBody DeviceTokenDeleteRequest request) {
        deviceTokenService.unregister(userId, request);
        return ResponseEntity.noContent().build();
    }
}
