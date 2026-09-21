package com.ssafy.ddubi.domain.auth.dto.request;

import com.ssafy.ddubi.domain.auth.client.OAuthCodeExchange;
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
 * <h3>세 가지 중 하나를 보낸다</h3>
 * <table>
 *   <tr><th>제공자</th><th>앱이 받아오는 것</th><th>보낼 필드</th></tr>
 *   <tr><td>카카오</td><td>SDK 액세스 토큰</td><td>{@code accessToken}</td></tr>
 *   <tr><td>구글</td><td>OIDC ID 토큰</td><td>{@code idToken}</td></tr>
 *   <tr><td>네이버</td><td>웹 로그인 인가 코드</td><td>{@code authorizationCode} + {@code redirectUri} + {@code state}</td></tr>
 * </table>
 *
 * <p>네이버가 인가 코드인 이유: 앱이 네이버 SDK 가 아니라 웹 창으로 로그인해서 토큰이
 * 아니라 코드만 돌아온다. 코드를 토큰으로 바꾸는 일은 클라이언트 비밀키가 필요해서
 * 서버가 한다 — 로그인({@code POST /auth/oauth})이 이미 하는 것과 같은 교환이다.
 * 이 입구가 없어서 앱의 네이버 연결 칸이 비어 있었다(2026-09-18).
 *
 * @param accessToken       카카오·네이버 SDK 액세스 토큰
 * @param idToken           구글 OIDC ID 토큰
 * @param authorizationCode 제공자에게 받은 일회용 인가 코드
 * @param redirectUri       인가 코드 발급에 쓴 것과 동일해야 한다
 * @param state             CSRF 방지용 난수. 네이버만 필수다
 */
@Schema(description = "마이페이지 소셜 연결 요청")
public record SocialLinkRequest(

        @Schema(description = "연결할 소셜", example = "NAVER")
        @NotNull(message = "provider 는 필수입니다.")
        AuthProvider provider,

        @Schema(description = "KAKAO·NAVER SDK 액세스 토큰")
        String accessToken,

        @Schema(description = "GOOGLE 은 이 값을 보낸다")
        String idToken,

        @Schema(description = "NAVER 웹 로그인은 이 값을 보낸다")
        String authorizationCode,

        @Schema(description = "인가 코드를 받을 때 쓴 redirect_uri (authorizationCode 와 함께 필수)")
        String redirectUri,

        @Schema(description = "인가 요청에 쓴 CSRF 방지용 난수. NAVER 만 필수")
        String state
) {

    /** 기존 호출부(액세스 토큰·ID 토큰 방식)를 그대로 두기 위한 간단 생성자. */
    public SocialLinkRequest(AuthProvider provider, String accessToken, String idToken) {
        this(provider, accessToken, idToken, null, null, null);
    }

    /** 셋 중 하나는 있어야 한다. 전부 없으면 검증할 것이 없다. */
    public boolean hasNoCredential() {
        return !StringUtils.hasText(accessToken)
                && !StringUtils.hasText(idToken)
                && !StringUtils.hasText(authorizationCode);
    }

    /**
     * 인가 코드로 보냈는가. 액세스 토큰·ID 토큰보다 <b>먼저</b> 본다 —
     * 세 값이 함께 오면 서버가 직접 교환한 쪽이 더 믿을 만하다.
     */
    public boolean usesAuthorizationCode() {
        return StringUtils.hasText(authorizationCode);
    }

    public boolean usesIdToken() {
        return StringUtils.hasText(idToken);
    }

    public OAuthCodeExchange toExchange() {
        return new OAuthCodeExchange(authorizationCode, redirectUri, state);
    }
}
