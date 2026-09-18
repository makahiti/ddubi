package com.ssafy.ddubi.domain.notification.entity;

/**
 * 알림을 보내지 않은 이유. {@link NotificationStatus#SUPPRESSED} 일 때만 채워진다.
 *
 * <h3>왜 status 만으로는 부족한가</h3>
 * 세 경우가 전부 SUPPRESSED 로 보이는데, <b>해야 할 일이 서로 다르다.</b>
 * 특히 FCM 을 붙이는 초기에는 토큰이 없어 {@link #NO_VALID_DEVICE} 가 흔한데,
 * 이것을 {@link #DUPLICATE} 로 오독하면 "중복 억제가 잘 되는구나" 하고 넘어가게 된다.
 * 정작 확인하려던 억제 동작은 검증되지 않은 채로 남는다.
 *
 * <p>억제율을 세려면 억제 사실 자체가 데이터여야 한다는 것이 도메인 규칙 9의 근거다.
 * 같은 이유로 <b>왜</b> 억제했는지도 데이터여야 한다.
 */
public enum SuppressReason {

    /** 같은 suppressKey 를 TTL(기본 10분) 안에 이미 보냈다. 정상 동작이다. */
    DUPLICATE,

    /** 사용자가 알림을 꺼 뒀다({@code user.is_alarm=false}). 행은 남으므로 알림함에서는 보인다. */
    ALARM_OFF,

    /** 유효한 FCM 토큰이 하나도 없다. 기기 등록을 먼저 해야 한다. */
    NO_VALID_DEVICE
}
