package com.ssafy.ddubi.domain.auth.dto.response;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 소셜 로그인 결과. <b>두 가지 중 하나</b>다.
 *
 * <pre>
 * AUTHENTICATED         로그인 완료. tokens 가 채워진다
 * ACCOUNT_LINK_REQUIRED 같은 이메일의 기존 계정이 있다. linkToken 으로 연동을 진행한다
 * </pre>
 *
 * <h3>왜 오류가 아니라 성공 응답인가</h3>
 * 연동 요구는 실패가 아니라 <b>흐름의 다음 단계</b>다. 오류로 주면 프론트의 공통 오류
 * 처리기가 "로그인 실패" 토스트를 띄우고 끝난다. 그리고 우리 오류 본문에는 데이터를 담을
 * 자리가 없어서 {@code linkToken} 을 실을 수도 없다.
 *
 * <p>프론트는 {@code status} 로 분기한다.
 */
@Schema(description = "소셜 로그인 결과 — 로그인 완료 또는 계정 연동 필요")
public record OAuthLoginResponse(

        @Schema(description = "AUTHENTICATED | ACCOUNT_LINK_REQUIRED")
        Status status,

        @Schema(description = "status=AUTHENTICATED 일 때만")
        AuthTokenResponse tokens,

        @Schema(description = "status=ACCOUNT_LINK_REQUIRED 일 때만. 5분 일회용",
                example = "3Qk1_9vZ...")
        String linkToken,

        @Schema(description = "연동하려는 소셜", example = "NAVER")
        AuthProvider provider,

        @Schema(description = "기존 계정 이메일(마스킹). 어느 계정인지 알려 주되 전체를 노출하지 않는다",
                example = "us***@naver.com")
        String email
) {

    public enum Status {
        AUTHENTICATED,
        ACCOUNT_LINK_REQUIRED
    }

    public static OAuthLoginResponse authenticated(AuthTokenResponse tokens) {
        return new OAuthLoginResponse(Status.AUTHENTICATED, tokens, null, null, null);
    }

    public static OAuthLoginResponse linkRequired(String linkToken, AuthProvider provider,
                                                  String maskedEmail) {
        return new OAuthLoginResponse(Status.ACCOUNT_LINK_REQUIRED, null,
                linkToken, provider, maskedEmail);
    }
}
