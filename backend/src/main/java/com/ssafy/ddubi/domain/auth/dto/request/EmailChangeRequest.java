package com.ssafy.ddubi.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AUTH-009 이메일 변경. <b>이미 로그인한 상태</b>에서 부른다.
 *
 * <h3>순서</h3>
 * <ol>
 *   <li>{@code POST /auth/email/verification} — 새 이메일로 코드 발송</li>
 *   <li>{@code POST /auth/email/verification/confirm} — 코드 확인</li>
 *   <li>여기 — 확인이 끝난 이메일로만 바꿀 수 있다</li>
 * </ol>
 * 가입과 같은 흐름을 그대로 쓴다. 새 이메일의 소유를 확인하지 않으면 남의 이메일을
 * 자기 계정에 달아 선점할 수 있고, 그러면 진짜 주인은 그 이메일로 가입하지 못한다.
 *
 * @param newEmail 바꿀 이메일. 인증을 마친 주소여야 한다
 * @param password 지금 쓰는 비밀번호. 비밀번호가 있는 계정만 필요하다
 */
@Schema(description = "이메일 변경 요청")
public record EmailChangeRequest(

        @Schema(description = "바꿀 이메일 (인증 완료된 주소)")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
        String newEmail,

        @Schema(description = "지금 쓰는 비밀번호. 소셜로만 가입한 계정은 비워 둔다")
        String password
) {
}
