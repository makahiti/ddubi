package com.ssafy.ddubi.domain.user.dto.response;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.user.entity.User;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * USER-001 / USER-002 응답. Notion API 명세서 v2.1.1 기준.
 *
 * <p>{@code withdrawnAt} 은 담지 않는다. ERD_master 의 user 테이블에 컬럼이 없다 — 탈퇴는 하드 삭제라 "탈퇴한 사용자"라는 상태 자체가
 * 존재하지 않는다(도메인 규칙 4). 명세서에서도 제거하기로 확정했다.
 *
 * <p>{@code provider} 는 계정에 붙은 인증 수단 중 하나다. 계정 통합으로 LOCAL·KAKAO 가 함께 있을 수 있어 가입 경로를 대표값으로 보아 LOCAL
 * 을 우선한다.
 */
public record UserResponse(
        Long id,
        AuthProvider provider,
        String email,
        String nickname,
        int notifyLeadMin,
        boolean alarmEnabled,
        LocalDateTime historyConsentedAt,
        LocalTime briefingTime,
        String mobilityNote,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserResponse of(User user, AuthProvider provider) {
        return new UserResponse(
                user.getId(),
                provider,
                user.getEmail(),
                user.getNickname(),
                user.getNotifyLeadMin(),
                user.isAlarm(),
                user.getHistoryConsentedAt(),
                user.getBriefingTime(),
                user.getMobilityNote(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
