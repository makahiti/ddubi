package com.ssafy.ddubi.global.mail;

import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 실제 발송. 자격증명이 설정돼 있을 때만 쓰인다(MailConfig).
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 수신 주소는 남기되 본문은 남기지 않는다 — 인증 코드가 로그에 남으면 안 된다.
            log.error("메일 발송 실패 to={} : {}", to, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
        log.info("메일 발송 완료 to={}", to);
    }
}
