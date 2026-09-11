package com.ssafy.ddubi.domain.auth.client;

/**
 * 소셜 제공자에게서 받아온 사용자 정보.
 *
 * <h3>이메일은 "검증된 것"만 계정 연동에 쓴다</h3>
 * 연동은 <b>같은 사람인가</b>를 판단하는 일이다. 제공자가 소유를 확인하지 않은 이메일을
 * 근거로 삼으면, 남의 이메일을 적어 만든 소셜 계정이 그 사람의 계정 연동 화면을 띄운다.
 * 비밀번호 확인이 뒤에 붙으므로 탈취까지 가지는 않지만, <b>그 이메일로 계정이 있다는 사실</b>
 * 자체가 새어 나간다.
 *
 * @param providerUserId 제공자의 고유 회원번호. 항상 존재한다
 * @param email          이메일. <b>NULL 일 수 있다</b> — 카카오에서 제공에 동의하지 않은 경우.
 *                       이 경우 기존 계정과 연동할 수 없어 별도 계정이 만들어진다
 * @param emailVerified  제공자가 이 이메일의 소유를 확인했는가.
 *                       {@code false} 면 연동 후보로 쓰지 않는다
 * @param nickname       표시 이름. 없으면 서비스가 기본값을 만든다
 */
public record OAuthUserInfo(
        String providerUserId,
        String email,
        boolean emailVerified,
        String nickname
) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    /** 계정 연동 후보로 삼아도 되는 이메일인가. */
    public boolean hasVerifiedEmail() {
        return hasEmail() && emailVerified;
    }
}
