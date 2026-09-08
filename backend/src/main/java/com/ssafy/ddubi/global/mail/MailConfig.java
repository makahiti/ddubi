package com.ssafy.ddubi.global.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * 발송기 선택. 프로파일이 아니라 <b>자격증명 유무</b>로 고른다.
 *
 * 프로파일로 가르면 "dev 인데 실제로 보내보고 싶다" 를 못 하고,
 * 반대로 운영 프로파일인데 키를 안 넣은 실수를 조용히 넘긴다.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public EmailSender emailSender(MailProperties properties, JavaMailSender mailSender) {
        if (!StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getPassword())) {
            log.warn("MAIL_USERNAME/MAIL_PASSWORD 미설정 — 인증 메일을 실제로 보내지 않고 로그에만 남깁니다");
            return new LoggingEmailSender();
        }
        log.info("메일 발송 준비 완료 — from={}", properties.getUsername());
        return new SmtpEmailSender(mailSender, properties.getUsername());
    }
}
