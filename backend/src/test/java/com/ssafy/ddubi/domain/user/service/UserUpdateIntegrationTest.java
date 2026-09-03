package com.ssafy.ddubi.domain.user.service;

import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.service.AuthService;
import com.ssafy.ddubi.domain.user.dto.request.TransportPrefRequest;
import com.ssafy.ddubi.domain.user.dto.request.UserUpdateRequest;
import com.ssafy.ddubi.domain.user.entity.TransportMode;
import com.ssafy.ddubi.domain.user.entity.TransportPreference;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * USER-002 부분 수정 규칙.
 *
 * 핵심은 <b>"필드 미전달"과 "명시적 null"의 구분</b>이다. 이게 깨지면 닉네임만 바꾸는 요청이
 * 브리핑 설정을 조용히 꺼버린다 — 예외도 로그도 남지 않아 발견이 매우 늦다.
 * 실제로 Optional 로 구현했을 때 이 버그가 났고, setter 호출 여부 플래그로 고쳤다.
 */
class UserUpdateIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;

    private Long userId;

    @BeforeEach
    void signup() {
        givenEmailVerified("update@ddubi.com");
        userId = authService.signupLocal(
                new LocalSignupRequest("update@ddubi.com", "password123", "처음닉네임", "d1"))
                .user().id();
    }

    /** 세 필드를 채워둔 상태를 만든다. */
    private void givenAllFieldsSet() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setBriefingTime(LocalTime.of(7, 0));
        request.setMobilityNote("계단 이동 어려움");
        request.setTransportPrefs(List.of(
                new TransportPrefRequest(TransportMode.SUBWAY, TransportPreference.PREFER)));
        userService.update(userId, request);
    }

    @Test
    @DisplayName("보내지 않은 필드는 그대로 둔다 — 닉네임만 바꿔도 브리핑·메모·취향이 살아 있다")
    void keepsFieldsThatWereNotSent() {
        givenAllFieldsSet();

        UserUpdateRequest onlyNickname = new UserUpdateRequest();
        onlyNickname.setNickname("바뀐닉네임");
        var updated = userService.update(userId, onlyNickname);

        assertThat(updated.nickname()).isEqualTo("바뀐닉네임");
        assertThat(updated.briefingTime()).as("미전달 필드가 지워지면 안 된다").isEqualTo(LocalTime.of(7, 0));
        assertThat(updated.mobilityNote()).isEqualTo("계단 이동 어려움");
        assertThat(updated.transportPrefs()).hasSize(1);
    }

    @Test
    @DisplayName("명시적 null 은 값을 지운다 — 브리핑 끄기")
    void explicitNullClearsTheValue() {
        givenAllFieldsSet();

        UserUpdateRequest clearBriefing = new UserUpdateRequest();
        clearBriefing.setBriefingTime(null);
        var updated = userService.update(userId, clearBriefing);

        assertThat(updated.briefingTime()).isNull();
        assertThat(updated.mobilityNote()).as("다른 필드는 건드리지 않는다").isEqualTo("계단 이동 어려움");
    }

    @Test
    @DisplayName("transportPrefs 는 보낸 배열로 전체 교체한다 — 병합이 아니다")
    void replacesTransportPrefsEntirely() {
        UserUpdateRequest first = new UserUpdateRequest();
        first.setTransportPrefs(List.of(
                new TransportPrefRequest(TransportMode.SUBWAY, TransportPreference.PREFER),
                new TransportPrefRequest(TransportMode.BUS, TransportPreference.AVOID)));
        userService.update(userId, first);

        UserUpdateRequest second = new UserUpdateRequest();
        second.setTransportPrefs(List.of(
                new TransportPrefRequest(TransportMode.BUS, TransportPreference.PREFER)));
        var updated = userService.update(userId, second);

        assertThat(updated.transportPrefs()).hasSize(1);
        assertThat(updated.transportPrefs().get(0).mode()).isEqualTo(TransportMode.BUS);
        assertThat(updated.transportPrefs().get(0).preference())
                .as("같은 mode 의 이전 값이 남아 있으면 안 된다").isEqualTo(TransportPreference.PREFER);
    }

    @Test
    @DisplayName("빈 배열을 보내면 취향을 전부 지운다")
    void emptyArrayClearsAllPrefs() {
        givenAllFieldsSet();

        UserUpdateRequest clear = new UserUpdateRequest();
        clear.setTransportPrefs(List.of());
        var updated = userService.update(userId, clear);

        assertThat(updated.transportPrefs()).isEmpty();
    }

    @Test
    @DisplayName("같은 교통수단을 두 번 지정하면 거부한다 — UNIQUE(user_id, mode)")
    void rejectsDuplicateMode() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setTransportPrefs(List.of(
                new TransportPrefRequest(TransportMode.BUS, TransportPreference.PREFER),
                new TransportPrefRequest(TransportMode.BUS, TransportPreference.AVOID)));

        assertThatThrownBy(() -> userService.update(userId, request))
                .hasMessageContaining("두 번");
    }

    @Test
    @DisplayName("historyConsent 를 켜면 동의 시각이 기록되고, 끄면 지워진다")
    void togglesHistoryConsent() {
        UserUpdateRequest on = new UserUpdateRequest();
        on.setHistoryConsent(true);
        assertThat(userService.update(userId, on).historyConsentedAt()).isNotNull();

        UserUpdateRequest off = new UserUpdateRequest();
        off.setHistoryConsent(false);
        assertThat(userService.update(userId, off).historyConsentedAt()).isNull();
    }

    @Test
    @DisplayName("아무 필드도 없으면 거부한다")
    void rejectsEmptyRequest() {
        assertThatThrownBy(() -> userService.update(userId, new UserUpdateRequest()))
                .hasMessageContaining("변경할 항목이 없습니다");
    }
}
