package com.ssafy.ddubi.global.util;

import org.slf4j.MDC;

/**
 * 요청 단위 추적 ID 보관소. {@code RequestIdFilter} 가 채우고 응답 래퍼·로그가 읽는다.
 * MDC 를 쓰므로 로그 패턴에 {@code %X{requestId}} 를 넣으면 자동으로 찍힌다.
 */
public final class RequestIdHolder {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private RequestIdHolder() {
    }

    public static void set(String requestId) {
        MDC.put(MDC_KEY, requestId);
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
