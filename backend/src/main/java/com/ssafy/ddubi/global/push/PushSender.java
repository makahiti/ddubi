package com.ssafy.ddubi.global.push;

/**
 * 푸시 발송 창구.
 *
 * 인터페이스로 둔 이유는 {@code EmailSender} 와 같다 — 자격증명이 없는 팀원도
 * 알림 흐름을 끝까지 돌려볼 수 있어야 한다. 구현체 선택은 PushConfig 가 한다.
 */
public interface PushSender {

    /** 예외를 던지지 않는다. 실패도 결과로 돌려준다. */
    PushResult send(PushMessage message);
}
