package com.ssafy.ddubi.global.push;

/**
 * 푸시 한 건. 어느 기기로 보낼지는 호출부가 정한다.
 *
 * @param fcmToken 대상 기기 토큰
 * @param title    알림 제목
 * @param body     알림 본문
 * @param data     앱이 탭했을 때 어디로 갈지 판단할 값(notificationId, scheduleId 등)
 */
public record PushMessage(
        String fcmToken,
        String title,
        String body,
        java.util.Map<String, String> data
) {
}
