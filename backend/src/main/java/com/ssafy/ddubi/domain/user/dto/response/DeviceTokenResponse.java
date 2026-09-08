package com.ssafy.ddubi.domain.user.dto.response;

import com.ssafy.ddubi.domain.user.entity.DevicePlatform;
import com.ssafy.ddubi.domain.user.entity.DeviceToken;

import java.time.LocalDateTime;

/** DEVICE-001 응답. 명세서: deviceTokenId, platform, valid, lastUsedAt. */
public record DeviceTokenResponse(
        Long deviceTokenId,
        DevicePlatform platform,
        boolean valid,
        LocalDateTime lastUsedAt
) {

    public static DeviceTokenResponse of(DeviceToken token) {
        return new DeviceTokenResponse(
                token.getId(), token.getPlatform(), token.isValid(), token.getLastUsedAt());
    }
}
