package com.ssafy.ddubi.domain.auth.dto.request;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.util.StringUtils;

/**
 * 마이페이지에서 소셜 계정을 추가로 연결한다. <b>이미 로그인한 상태</b>에서 부른다.
 *
 * <h3>왜 비밀번호를 받지 않나</h3>
 * 로그인 전 연동({@link OAuthLinkRequest})은 "이 계정이 정말 당신 것인가" 를 물어야 해서
 * 비밀번호가 필요했다. 여기서는 <b>이미 그 계정으로 인증된 상태</b>다. 다시 물을 이유가 없고,
 * 물으면 소셜로만 가입한 사용자(비밀번호 자체가 없는)는 영영 연결하지 못한다.
 *
 * @param accessToken 카카오·네이버 SDK 액세스 토큰
 * @param idToken     구글 OIDC ID 토큰
 */
@Schema(description = "마이페이지 소셜 연결 요청")
public record SocialLinkRequest(

        @Schema(description = "연결할 소셜", example = "NAVER")
        @NotNull(message = "provider 는 필수입니다.")
        AuthProvider provider,

        @Schema(description = "KAKAO·NAVER 는 이 값을 보낸다")
        String accessToken,

        @Schema(description = "GOOGLE 은 이 값을 보낸다")
        String idToken
) {

    /** 둘 중 정확히 하나는 있어야 한다. 둘 다 없으면 검증할 것이 없다. */
    public boolean hasNoCredential() {
        return !StringUtils.hasText(accessToken) && !StringUtils.hasText(idToken);
    }

    public boolean usesIdToken() {
        return StringUtils.hasText(idToken);
    }
}
