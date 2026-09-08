package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationRequest;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.mail.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 회원가입 이메일 인증 (AUTH-006 / AUTH-007).
 *
 * <pre>
 * 1) 코드 발송   POST /auth/email/verification
 * 2) 코드 확인   POST /auth/email/verification/confirm  → 통행증 발급
 * 3) 가입        POST /auth/local/signup                → 통행증 소비
 * </pre>
 *
 * <h3>왜 필요한가</h3>
 * 이메일이 계정 통합의 유일한 키다(도메인 규칙 3). 소유를 확인하지 않으면
 * 남의 이메일로 로컬 계정을 먼저 만들어 두고, 그 사람이 소셜 로그인할 때
 * 자동 통합되는 순간 계정을 통째로 가져갈 수 있다.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** 6자리. 자릿수를 늘리기보다 시도 횟수로 막는다 — 사람이 옮겨 적어야 하는 값이다. */
    private static final int CODE_BOUND = 1_000_000;
    private static final String CODE_FORMAT = "%06d";

    /** 넘기면 코드를 폐기한다. 6자리는 상한이 없으면 대입으로 뚫린다. */
    private static final long MAX_ATTEMPTS = 5;

    private static final String SUBJECT = "[뚜비] 이메일 인증 코드";

    private final EmailVerificationStore store;
    private final EmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    private final Duration codeTtl;
    private final Duration cooldown;
    private final Duration verifiedTtl;

    public EmailVerificationService(
            EmailVerificationStore store,
            EmailSender emailSender,
            @Value("${ddubi.auth.email-verification.code-ttl}") Duration codeTtl,
            @Value("${ddubi.auth.email-verification.resend-cooldown}") Duration cooldown,
            @Value("${ddubi.auth.email-verification.verified-ttl}") Duration verifiedTtl) {
        this.store = store;
        this.emailSender = emailSender;
        this.codeTtl = codeTtl;
        this.cooldown = cooldown;
        this.verifiedTtl = verifiedTtl;
    }

    /**
     * 코드를 만들어 보낸다.
     *
     * 이미 가입된 이메일이어도 그냥 보낸다 — 여기서 거절하면 어떤 이메일이 가입돼
     * 있는지 알아내는 통로가 된다. 중복은 가입 단계에서 409 로 걸린다.
     */
    public void sendCode(EmailVerificationRequest request) {
        String email = request.email();
        if (store.isOnCooldown(email)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "인증 메일을 방금 보냈습니다. 잠시 후 다시 시도해 주세요.");
        }

        String code = CODE_FORMAT.formatted(random.nextInt(CODE_BOUND));
        // 저장이 먼저다. 발송이 성공했는데 저장이 안 된 코드가 돌아다니면 안 된다.
        store.saveCode(email, code, codeTtl, cooldown);
        emailSender.send(email, SUBJECT, body(code));
        log.info("이메일 인증 코드 발송 — {}", mask(email));
    }

    /** 코드를 확인하고 통행증을 발급한다. */
    public void confirm(EmailVerificationConfirmRequest request) {
        String email = request.email();
        String saved = store.findCode(email);
        if (saved == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "인증 코드가 만료되었습니다. 다시 요청해 주세요.");
        }

        if (!saved.equals(request.code())) {
            long attempts = store.increaseAttempt(email);
            if (attempts >= MAX_ATTEMPTS) {
                store.discardCode(email);
                log.warn("이메일 인증 시도 초과 — {} ({}회)", mask(email), attempts);
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "인증 시도 횟수를 초과했습니다. 코드를 다시 요청해 주세요.");
            }
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "인증 코드가 일치하지 않습니다. (%d/%d)".formatted(attempts, MAX_ATTEMPTS));
        }

        store.discardCode(email);
        store.markVerified(email, verifiedTtl);
        log.info("이메일 인증 완료 — {}", mask(email));
    }

    /**
     * 통행증이 있는지 본다. 없애지는 않는다.
     *
     * 소비와 나눈 이유: 가입이 중복 이메일로 실패하면 통행증이 살아 있어야 한다.
     * 확인하자마자 없애면 사용자가 이유 없이 인증을 다시 해야 한다.
     *
     * @throws BusinessException 인증하지 않았거나 통행증이 만료됐을 때
     */
    public void requireVerified(String email) {
        if (!store.isVerified(email)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이메일 인증이 필요합니다. 인증을 먼저 완료해 주세요.");
        }
    }

    /**
     * 통행증을 쓴다. 가입이 실제로 끝난 뒤에 호출한다.
     * 한 번 인증한 이메일로 계정을 여러 개 만드는 것을 막는다.
     */
    public void consume(String email) {
        store.consumeVerified(email);
    }

    private String body(String code) {
        return """
                뚜비 회원가입 인증 코드입니다.

                    %s

                %d분 안에 입력해 주세요.

                ※ 메일이 보이지 않으면 스팸함을 확인해 주세요.
                ※ 본인이 요청하지 않았다면 이 메일을 무시하시면 됩니다."""
                .formatted(code, codeTtl.toMinutes());
    }

    /** 로그에 이메일 전체를 남기지 않는다. */
    private String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
