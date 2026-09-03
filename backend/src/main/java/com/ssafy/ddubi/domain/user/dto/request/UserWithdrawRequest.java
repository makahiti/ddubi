package com.ssafy.ddubi.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * USER-003 회원 탈퇴. 명세서: password(LOCAL 회원 필수), confirmation=WITHDRAW(필수), reason(선택).
 *
 * 탈퇴는 되돌릴 수 없다 — user 행 하나를 지우면 일정·경로·추천·알림이 전부 CASCADE 로
 * 사라지고 보관본이 없다(도메인 규칙 4). 그래서 두 겹으로 막는다.
 *  - confirmation: 실수로 호출되는 것을 막는다
 *  - password: 토큰이 탈취된 상태에서 남이 탈퇴시키는 것을 막는다(LOCAL 회원)
 */
public record UserWithdrawRequest(
        /** LOCAL 인증 수단이 있으면 필수. 소셜 전용 계정은 비밀번호가 없어 생략한다. */
        String password,

        @NotBlank(message = "confirmation 은 필수입니다.")
        String confirmation,

        String reason
) {

    public static final String CONFIRM_KEYWORD = "WITHDRAW";

    public boolean isConfirmed() {
        return CONFIRM_KEYWORD.equals(confirmation);
    }
}
