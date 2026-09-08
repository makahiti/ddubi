package com.ssafy.ddubi.domain.notification.entity;

/**
 * 발송 결과. 억제되어도 행은 만들고 여기에 SUPPRESSED 를 남긴다(도메인 규칙 9).
 *
 * 사용자가 "왜 안 왔지" 할 때 알림함에 근거가 있어야 하고,
 * 억제율을 세려면 억제 사실 자체가 데이터여야 한다.
 */
public enum NotificationStatus {

    /** FCM 발송 성공 */
    SENT,
    /** FCM 발송 시도했으나 실패 */
    FAILED,
    /** 발송하지 않음 — 알림 끔, 중복 */
    SUPPRESSED
}
