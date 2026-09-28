package com.ssafy.ddubi.domain.user.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * USER-002 내 정보 변경. 명세서: 변경할 필드만 전달한다.
 *
 * <h3>세 가지 상태를 구분해야 한다</h3>
 *
 * <pre>
 *   필드 없음        → 기존값 유지
 *   "field": null    → 값을 지운다 (브리핑 끔, 메모 삭제)
 *   "field": 값      → 그 값으로 변경
 * </pre>
 *
 * <h3>왜 record 가 아니라 클래스인가</h3>
 *
 * record 로 만들면 앞의 두 경우를 구분할 수 없다. {@code Optional} 도 답이 아니다 — Jackson 의 Jdk8Module 은 <b>미전달과 명시적
 * null 을 똑같이 Optional.empty()</b> 로 만든다. 그대로 두면 닉네임만 바꾸는 요청이 브리핑 설정을 조용히 꺼버린다.
 *
 * <p>Jackson 은 <b>JSON 에 키가 있을 때만 setter 를 호출</b>하므로, setter 안에서 플래그를 세워 "전달됨"을 기록한다. 지울 수 있는 필드에만
 * 이 방식을 쓴다.
 */
public class UserUpdateRequest {

    @Size(min = 1, max = 50, message = "닉네임은 1~50자여야 합니다.")
    private String nickname;

    @Min(value = 0, message = "알림 리드타임은 0분 이상이어야 합니다.")
    @Max(value = 180, message = "알림 리드타임은 180분 이하여야 합니다.")
    private Integer notifyLeadMin;

    private Boolean alarmEnabled;

    /** true 면 지금 시각으로 동의, false 면 철회. 이미 동의한 상태에서 true 면 시각을 유지한다. */
    private Boolean historyConsent;

    /** 아침 브리핑 시각. 명시적 null 이면 브리핑을 끈다 (REQ-NTF-003). */
    private LocalTime briefingTime;

    private boolean briefingTimePresent;

    /** 밤 브리핑 시각. 명시적 null 이면 끈다. 내일 일정을 전날 밤에 알린다. */
    private LocalTime nightBriefingTime;

    private boolean nightBriefingTimePresent;

    /** 이동 시 어려운 점. 명시적 null 이면 지운다 (REQ-SCH-004). */
    @Size(max = 100, message = "이동 메모는 100자를 넘을 수 없습니다.")
    private String mobilityNote;

    private boolean mobilityNotePresent;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getNotifyLeadMin() {
        return notifyLeadMin;
    }

    public void setNotifyLeadMin(Integer notifyLeadMin) {
        this.notifyLeadMin = notifyLeadMin;
    }

    public Boolean getAlarmEnabled() {
        return alarmEnabled;
    }

    public void setAlarmEnabled(Boolean alarmEnabled) {
        this.alarmEnabled = alarmEnabled;
    }

    public Boolean getHistoryConsent() {
        return historyConsent;
    }

    public void setHistoryConsent(Boolean historyConsent) {
        this.historyConsent = historyConsent;
    }

    public LocalTime getBriefingTime() {
        return briefingTime;
    }

    public void setBriefingTime(LocalTime briefingTime) {
        this.briefingTime = briefingTime;
        this.briefingTimePresent = true;
    }

    /** JSON 에 briefingTime 키가 있었는가. 값이 null 이어도 true 다. */
    public boolean isBriefingTimePresent() {
        return briefingTimePresent;
    }

    public String getMobilityNote() {
        return mobilityNote;
    }

    public void setMobilityNote(String mobilityNote) {
        this.mobilityNote = mobilityNote;
        this.mobilityNotePresent = true;
    }

    /** JSON 에 mobilityNote 키가 있었는가. 값이 null 이어도 true 다. */
    public boolean isMobilityNotePresent() {
        return mobilityNotePresent;
    }

    public LocalTime getNightBriefingTime() {
        return nightBriefingTime;
    }

    public void setNightBriefingTime(LocalTime nightBriefingTime) {
        this.nightBriefingTime = nightBriefingTime;
        this.nightBriefingTimePresent = true;
    }

    /** JSON 에 nightBriefingTime 키가 있었는가. 값이 null 이어도 true 다. */
    public boolean isNightBriefingTimePresent() {
        return nightBriefingTimePresent;
    }

    /** 변경할 게 하나도 없으면 굳이 UPDATE 를 돌리지 않는다. */
    public boolean isEmpty() {
        return nickname == null
                && notifyLeadMin == null
                && alarmEnabled == null
                && historyConsent == null
                && !briefingTimePresent
                && !nightBriefingTimePresent
                && !mobilityNotePresent;
    }
}
