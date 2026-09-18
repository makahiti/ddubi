package com.ssafy.ddubi.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 계정 연동 확정. 소셜 로그인이 {@code ACCOUNT_LINK_REQUIRED} 를 준 뒤에 부른다.
 *
 * <p>소셜 액세스 토큰은 <b>받지 않는다.</b> 누구를 어디에 붙일지는 서버가 {@code linkToken}
 * 으로 들고 있다. 프론트가 소셜 토큰을 화면 사이에 저장하지 않아도 되게 하기 위한 것이다.
 */
@Schema(description = "계정 연동 확정 요청")
public record OAuthLinkRequest(

        @Schema(description = "소셜 로그인 응답으로 받은 일회용 티켓")
        @NotBlank(message = "linkToken 은 필수입니다.")
        String linkToken,

        @Schema(description = "기존 이메일 계정의 비밀번호")
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password,

        @Schema(description = "기기 식별자. 로그인과 같은 값을 쓴다")
        String deviceId
) {
}
