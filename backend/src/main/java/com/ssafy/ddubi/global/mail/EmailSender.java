package com.ssafy.ddubi.global.mail;

/**
 * 메일 발송 창구.
 *
 * 인터페이스로 둔 이유가 둘이다.
 *  1) 자격증명이 없는 팀원도 가입 흐름을 돌릴 수 있어야 한다 — 로그 발송기로 대체된다
 *  2) Gmail 로 보낸 메일이 네이버 스팸함에 걸리면 발송 경로만 갈아끼운다
 */
public interface EmailSender {

    /**
     * @throws com.ssafy.ddubi.global.exception.BusinessException 발송 실패 시.
     *         호출부가 "보냈다"고 응답한 뒤 실제로 안 가는 상황을 만들지 않기 위해 예외로 알린다
     */
    void send(String to, String subject, String body);
}
