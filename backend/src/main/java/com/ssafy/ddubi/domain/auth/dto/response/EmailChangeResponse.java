package com.ssafy.ddubi.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AUTH-009 이메일 변경 응답.
 *
 * <p>토큰을 다시 주지 않는다. 토큰은 사용자 번호로 발급되므로 이메일이 바뀌어도 그대로 쓴다.
 * 다만 <b>다음 로그인부터는 새 이메일</b>을 써야 한다.
 *
 * @param email             바뀐 이메일
 * @param loginEmailChanged 로컬 로그인 아이디도 함께 바뀌었는지. 소셜 전용 계정이면 false
 */
@Schema(description = "이메일 변경 결과")
public record EmailChangeResponse(String email, boolean loginEmailChanged) {
}
