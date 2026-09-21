package com.ssafy.ddubi.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AUTH-008 비밀번호 변경. <b>이미 로그인한 상태</b>에서 부른다.
 *
 * <h3>왜 지금 쓰는 비밀번호를 또 받나</h3>
 * 액세스 토큰만으로 비밀번호를 바꾸게 하면, 잠깐 자리를 비운 사이 폰을 집어 든 사람이
 * 주인을 계정에서 밀어낼 수 있다. 토큰은 "이 기기가 로그인돼 있다" 는 증거일 뿐
 * "지금 조작하는 사람이 주인이다" 는 증거는 아니다.
 *
 * @param currentPassword 지금 쓰는 비밀번호
 * @param newPassword     새 비밀번호. 가입과 같은 규칙(8~72자)
 * @param deviceId        이 기기 식별자. 주면 바꾼 뒤에도 이 기기는 로그인 상태가 유지된다
 */
@Schema(description = "비밀번호 변경 요청")
public record PasswordChangeRequest(

        @Schema(description = "지금 쓰는 비밀번호")
        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @Schema(description = "새 비밀번호 (8~72자)")
        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다.")
        String newPassword,

        @Schema(description = "이 기기 식별자. 없으면 서버가 새로 발급한다")
        String deviceId
) {
}
