package com.ssafy.ddubi.domain.auth.client;

/**
 * 인가 코드를 토큰으로 바꾸는 데 필요한 입력.
 *
 * 파라미터를 하나로 묶은 이유: 제공자마다 요구하는 값이 다르다.
 * 네이버는 {@code state} 를 필수로 요구하고, 카카오·구글은 쓰지 않는다.
 * 인터페이스에 인자를 계속 늘리는 대신 여기에 모은다.
 *
 * @param authorizationCode 제공자에게 받은 일회용 코드
 * @param redirectUri       코드 발급에 쓰인 것과 동일해야 한다
 * @param state             CSRF 방지용 난수. <b>네이버만 필수</b>이고 나머지는 null 이어도 된다
 */
public record OAuthCodeExchange(
        String authorizationCode,
        String redirectUri,
        String state
) {
}
