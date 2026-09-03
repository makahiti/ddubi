package com.ssafy.ddubi.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** AUTH-007 이메일 인증 코드 확인. */
public record EmailVerificationConfirmRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "인증 코드는 필수입니다.")
        @Pattern(regexp = "[0-9]{6}", message = "인증 코드는 숫자 6자리입니다.")
        String code
) {
}
