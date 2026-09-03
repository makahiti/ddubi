package com.ssafy.ddubi.domain.auth.dto.response;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.user.entity.User;

/**
 * 인증 응답에 포함되는 사용자 요약. Notion API 명세서 v2.1.1 이 정본이다.
 *
 * provider 는 <b>이번 요청에서 인증에 사용한 수단</b> 하나다.
 * 계정에 LOCAL 과 KAKAO 가 함께 붙어 있어도(계정 통합, 도메인 규칙 3)
 * 여기에는 방금 쓴 수단만 담는다. 연결된 수단 전체가 필요하면 별도 필드로 논의한다.
 */
public record AuthUserResponse(
        Long id,
        AuthProvider provider,
        String email,
        String nickname
) {

    public static AuthUserResponse of(User user, AuthProvider provider) {
        return new AuthUserResponse(user.getId(), provider, user.getEmail(), user.getNickname());
    }
}
