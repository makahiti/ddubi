package com.ssafy.ddubi.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** AUTH-006 이메일 인증 코드 발송. */
public record EmailVerificationRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
        String email
) {
}
