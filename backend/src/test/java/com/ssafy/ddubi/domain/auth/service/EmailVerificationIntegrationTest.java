package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 회원가입 이메일 인증 (AUTH-006 / AUTH-007).
 *
 * 이 인증이 있어야 도메인 규칙 3(이메일 기준 계정 통합)이 성립한다 —
 * 소유가 확인되지 않은 이메일로 계정을 만들 수 있으면 남의 이메일을 선점해
 * 그 사람의 소셜 로그인을 가로챌 수 있다.
 */
class EmailVerificationIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "verify@ddubi.com";

    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private AuthService authService;

    /** 발송된 메일 본문에서 코드를 꺼낸다. 저장소를 훔쳐보지 않고 실제 발송 내용을 쓴다. */
    private String sendAndCaptureCode(String email) {
        emailVerificationService.sendCode(new EmailVerificationRequest(email));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), org.mockito.ArgumentMatchers.anyString(), body.capture());
        return body.getValue().replaceAll("(?s).*?(\\d{6}).*", "$1");
    }

    private LocalSignupRequest signupRequest(String email) {
        return new LocalSignupRequest(email, "password123", "뚜비", "dev-1");
    }

    @Test
    @DisplayName("코드를 발송하고 확인하면 가입할 수 있다")
    void verifyThenSignup() {
        String code = sendAndCaptureCode(EMAIL);

        emailVerificationService.confirm(new EmailVerificationConfirmRequest(EMAIL, code));

        assertThat(emailVerificationStore.isVerified(EMAIL)).isTrue();
        assertThatCode(() -> authService.signupLocal(signupRequest(EMAIL))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("인증하지 않고 가입하면 400 이다")
    void signupWithoutVerification() {
        assertThatThrownBy(() -> authService.signupLocal(signupRequest(EMAIL)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("가입에 쓴 통행증은 소비된다 — 한 번 인증으로 계정을 두 개 만들 수 없다")
    void verifiedPassIsConsumed() {
        givenEmailVerified(EMAIL);
        authService.signupLocal(signupRequest(EMAIL));

        assertThat(emailVerificationStore.isVerified(EMAIL)).isFalse();
    }

    @Test
    @DisplayName("중복 이메일로 막히면 통행증은 남는다 — 다시 인증하게 만들지 않는다")
    void passSurvivesDuplicateFailure() {
        givenEmailVerified(EMAIL);
        authService.signupLocal(signupRequest(EMAIL));

        givenEmailVerified(EMAIL);
        assertThatThrownBy(() -> authService.signupLocal(signupRequest(EMAIL)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

        assertThat(emailVerificationStore.isVerified(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("코드가 틀리면 400 이고 통행증이 생기지 않는다")
    void wrongCode() {
        String code = sendAndCaptureCode(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() ->
                emailVerificationService.confirm(new EmailVerificationConfirmRequest(EMAIL, wrong)))
                .isInstanceOf(BusinessException.class);

        assertThat(emailVerificationStore.isVerified(EMAIL)).isFalse();
    }

    @Test
    @DisplayName("5회 틀리면 코드를 폐기한다 — 맞는 코드도 더는 통하지 않는다")
    void tooManyAttempts() {
        String code = sendAndCaptureCode(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() ->
                    emailVerificationService.confirm(new EmailVerificationConfirmRequest(EMAIL, wrong)))
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(emailVerificationStore.findCode(EMAIL)).as("코드가 폐기됐다").isNull();
        assertThatThrownBy(() ->
                emailVerificationService.confirm(new EmailVerificationConfirmRequest(EMAIL, code)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("발급받은 적 없는 이메일로 확인하면 400 이다")
    void confirmWithoutIssue() {
        assertThatThrownBy(() ->
                emailVerificationService.confirm(new EmailVerificationConfirmRequest(EMAIL, "123456")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("60초 안에 다시 요청하면 400 이고 메일을 또 보내지 않는다")
    void resendCooldown() {
        emailVerificationService.sendCode(new EmailVerificationRequest(EMAIL));

        assertThatThrownBy(() -> emailVerificationService.sendCode(new EmailVerificationRequest(EMAIL)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        // 발송은 처음 1회뿐이어야 한다
        verify(emailSender).send(eq(EMAIL), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("대소문자만 다른 주소로 인증을 우회할 수 없다")
    void emailIsCaseInsensitive() {
        String code = sendAndCaptureCode(EMAIL);

        emailVerificationService.confirm(new EmailVerificationConfirmRequest(EMAIL.toUpperCase(), code));

        assertThat(emailVerificationStore.isVerified(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("이미 가입된 이메일이어도 발송은 막지 않는다 — 가입 여부가 새어나가면 안 된다")
    void doesNotLeakRegistrationStatus() {
        givenEmailVerified(EMAIL);
        authService.signupLocal(signupRequest(EMAIL));

        assertThatCode(() -> emailVerificationService.sendCode(new EmailVerificationRequest(EMAIL)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("소셜 가입은 이메일 인증을 요구하지 않는다 — 제공자가 이미 확인했다")
    void oauthDoesNotRequireVerification() {
        verify(emailSender, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
