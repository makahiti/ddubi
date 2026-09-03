package com.ssafy.ddubi.global.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 메일 자격증명이 없을 때 쓰는 대체 발송기. 본문을 로그에 찍고 끝낸다.
 *
 * 이게 있는 이유: 팀원이 Gmail 앱 비밀번호 없이도 회원가입 흐름을 끝까지 돌릴 수 있어야 한다.
 * 카카오 키가 없어 로그인 자체를 못 돌려보던 문제를 되풀이하지 않는다.
 *
 * 운영에서 이 발송기가 뽑히면 인증 메일이 아무에게도 가지 않는다.
 * 기동 로그의 경고(MailConfig)로 그 사실을 알린다.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("""

                ┌───────────── 메일 발송 (로그 대체) ─────────────
                │ to      : {}
                │ subject : {}
                │ {}
                └────────────────────────────────────────────────""",
                to, subject, body.replace("\n", "\n│ "));
    }
}
