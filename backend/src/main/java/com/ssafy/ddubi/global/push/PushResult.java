package com.ssafy.ddubi.global.push;

/**
 * 발송 결과.
 *
 * 예외를 던지지 않고 결과 객체를 돌려주는 이유: 발송 실패는 <b>예상된 흐름</b>이다.
 * 앱을 지운 기기, 만료된 토큰이 늘 섞여 있고, 그때도 notification 행은 FAILED 로
 * 남겨야 한다(도메인 규칙 9). 예외로 처리하면 호출부가 매번 try-catch 를 쓰게 된다.
 *
 * @param tokenInvalid FCM 이 UNREGISTERED/INVALID_ARGUMENT 를 준 경우.
 *                     해당 device_token 을 무효화해야 한다 (REQ-NTF-004)
 */
public record PushResult(boolean sent, boolean tokenInvalid, String failureReason) {

    public static PushResult ok() {
        return new PushResult(true, false, null);
    }

    /** 일시적 실패 — 토큰은 살아 있다. */
    public static PushResult failed(String reason) {
        return new PushResult(false, false, reason);
    }

    /** 토큰이 죽었다 — 다시 보내도 소용없다. */
    public static PushResult invalidToken(String reason) {
        return new PushResult(false, true, reason);
    }
}
