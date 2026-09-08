package com.ssafy.ddubi.global.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FCM 자격증명이 없을 때 쓰는 대체 발송기. 내용을 로그에 찍고 성공으로 친다.
 *
 * 이게 있는 이유: 팀원이 Firebase 키 없이도 알림 흐름(억제 판정 → 발송 → 상태 기록)을
 * 끝까지 돌려볼 수 있어야 한다. 카카오 키·메일 키에서 이미 두 번 막혔던 문제다.
 *
 * 운영에서 이 발송기가 뽑히면 알림이 아무에게도 가지 않는다.
 * 기동 로그의 경고(PushConfig)로 그 사실을 알린다.
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public PushResult send(PushMessage message) {
        log.info("""

                ┌───────────── 푸시 발송 (로그 대체) ─────────────
                │ token : {}...
                │ title : {}
                │ body  : {}
                │ data  : {}
                └────────────────────────────────────────────────""",
                abbreviate(message.fcmToken()), message.title(), message.body(), message.data());
        return PushResult.ok();
    }

    /** 토큰 전체를 로그에 남기지 않는다 — 그것만 있으면 남의 기기로 푸시를 보낼 수 있다. */
    private String abbreviate(String token) {
        if (token == null || token.length() <= 12) {
            return "***";
        }
        return token.substring(0, 12);
    }
}
