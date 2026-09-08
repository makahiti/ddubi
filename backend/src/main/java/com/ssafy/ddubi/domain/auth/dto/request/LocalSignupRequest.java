package com.ssafy.ddubi.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** AUTH-001 로컬 회원가입. 명세서 v2.1.1: email·password·nickname 필수, deviceId 선택. */
public record LocalSignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다.")
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 1, max = 50, message = "닉네임은 1~50자여야 합니다.")
        String nickname,

        String deviceId
) {
}
