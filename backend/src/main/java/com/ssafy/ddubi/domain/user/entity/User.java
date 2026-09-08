package com.ssafy.ddubi.domain.user.entity;

import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 사용자 계정.
 *
 * 인증 수단은 {@code user_auth} 로 분리되어 있어 여기에는 로그인 방법과 무관한 속성만 둔다.
 * 탈퇴는 이 행을 DELETE 하는 것이고, 하위 데이터는 DB 의 ON DELETE CASCADE 가 지운다
 * (JPA cascade 를 걸지 않는다 — BACKEND_CONVENTION.md 3.1장 규칙 12).
 */
@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    public static final int DEFAULT_NOTIFY_LEAD_MIN = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 계정 통합 키. 소셜이 이메일을 안 주면 NULL 이고, 그 경우 통합되지 않는다. */
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    /** 이동 시 어려운 점 자유 입력 — 추천 여유시간 가중에 쓴다 (REQ-SCH-004). */
    @Column(name = "mobility_note", length = 100)
    private String mobilityNote;

    @Column(name = "notify_lead_min", nullable = false)
    private int notifyLeadMin;

    /** 아침 브리핑 수신 시각. NULL 이면 브리핑을 보내지 않는다 (REQ-NTF-003). */
    @Column(name = "briefing_time")
    private LocalTime briefingTime;

    /** 알림 전체 토글. false 여도 notification 행은 만들고 발송만 억제한다. */
    @Column(name = "is_alarm", nullable = false)
    private boolean alarm;

    /** 지오펜스 실측 동의 시각. NULL = 미동의 → actual_arrival 을 수집하지 않는다. */
    @Column(name = "history_consented_at")
    private LocalDateTime historyConsentedAt;

    @Builder
    private User(String email, String nickname) {
        this.email = email;
        this.nickname = nickname;
        this.notifyLeadMin = DEFAULT_NOTIFY_LEAD_MIN;
        this.alarm = true;
    }

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    /** 이동기록 동의 여부. report_detail.actual_arrival 수집 가부를 여기서 판단한다. */
    public boolean isHistoryConsented() {
        return historyConsentedAt != null;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changeMobilityNote(String mobilityNote) {
        this.mobilityNote = mobilityNote;
    }

    public void changeNotifyLeadMin(int notifyLeadMin) {
        this.notifyLeadMin = notifyLeadMin;
    }

    public void changeBriefingTime(LocalTime briefingTime) {
        this.briefingTime = briefingTime;
    }

    public void changeAlarm(boolean alarm) {
        this.alarm = alarm;
    }

    /**
     * 이동기록 동의 토글. 동의 시각은 서버가 찍고, 철회하면 NULL 로 되돌린다.
     * 철회해도 이미 저장된 실측 기록을 지우지는 않는다.
     */
    public void changeHistoryConsent(boolean consented) {
        this.historyConsentedAt = consented
                ? (historyConsentedAt != null ? historyConsentedAt : LocalDateTime.now())
                : null;
    }
}
