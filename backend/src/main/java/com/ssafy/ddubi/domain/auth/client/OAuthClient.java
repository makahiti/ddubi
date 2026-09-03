package com.ssafy.ddubi.domain.auth.client;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;

/**
 * 소셜 제공자에서 인가 코드를 사용자 정보로 바꾼다.
 *
 * 인터페이스로 둔 이유: authorizationCode 는 사람이 브라우저에서 로그인해야 나오는 값이라
 * 자동화 테스트에서 재현할 수 없다. 통합 테스트는 가짜 구현으로 갈아끼우고
 * 계정 통합 로직(도메인 규칙 3)만 검증한다.
 *
 * 구현체는 {@link AbstractOAuthClient} 를 상속해 제공자별 차이만 채운다.
 */
public interface OAuthClient {

    /** 이 구현이 담당하는 제공자. */
    AuthProvider provider();

    /** 인가 코드 → 액세스 토큰 → 사용자 정보. */
    OAuthUserInfo fetchUserInfo(OAuthCodeExchange exchange);

    /**
     * 모바일 SDK가 발급한 액세스 토큰 → 사용자 정보.
     * 현재 카카오와 네이버 모바일 로그인을 지원하며, 나머지 제공자는 명시적으로 거절한다.
     */
    default OAuthUserInfo fetchUserInfoFromAccessToken(String accessToken) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "해당 소셜 로그인은 모바일 액세스 토큰 방식을 지원하지 않습니다.");
    }

    /** OpenID Connect 제공자가 발급한 ID 토큰 → 사용자 정보. */
    default OAuthUserInfo fetchUserInfoFromIdToken(String idToken) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "해당 소셜 로그인은 ID 토큰 방식을 지원하지 않습니다.");
    }
}
