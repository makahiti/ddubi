package com.ssafy.ddubi.domain.auth.client;

/**
 * 소셜 제공자에게서 받아온 사용자 정보.
 *
 * @param providerUserId 제공자의 고유 회원번호. 항상 존재한다
 * @param email          이메일. <b>NULL 일 수 있다</b> — 카카오에서 이메일 제공에 동의하지 않은 경우.
 *                       이 경우 기존 계정과 통합할 수 없어 별도 계정이 만들어진다(도메인 규칙 3)
 * @param nickname       표시 이름. 없으면 서비스가 기본값을 만든다
 */
public record OAuthUserInfo(
        String providerUserId,
        String email,
        String nickname
) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
