package com.ssafy.ddubi.domain.auth.service;

/**
 * 이메일 마스킹. 연동 화면이 "어느 계정인지" 알려 주되 <b>전체를 노출하지는 않게</b> 한다.
 *
 * <pre>
 * user@naver.com   → us***@naver.com
 * ab@naver.com     → a***@naver.com     (2자)
 * a@naver.com      → ***@naver.com      (1자 — 남길 게 없다)
 * </pre>
 *
 * <p>도메인은 가리지 않는다. 사용자가 "아 그 네이버 계정" 하고 알아볼 단서가 필요한데,
 * 도메인까지 가리면 화면에 남는 정보가 없어 연동할지 판단할 수 없다.
 */
final class EmailMask {

    private static final String MASK = "***";

    private EmailMask() {
    }

    static String mask(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // 우리 DB 에 @ 없는 값이 들어올 일은 없지만, 그렇다고 원본을 그대로 흘리지는 않는다.
            return MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);

        int keep = Math.min(2, Math.max(0, local.length() - 1));
        return local.substring(0, keep) + MASK + domain;
    }
}
