package com.ssafy.ddubi.domain.auth.controller;

import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.EmailChangeRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LogoutRequest;
import com.ssafy.ddubi.domain.auth.dto.request.PasswordChangeRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthAccessTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLinkRequest;
import com.ssafy.ddubi.domain.auth.dto.request.SocialLinkRequest;
import com.ssafy.ddubi.domain.auth.dto.response.SocialLinkResponse;
import com.ssafy.ddubi.domain.auth.dto.request.TokenRefreshRequest;
import com.ssafy.ddubi.domain.auth.dto.response.AuthTokenResponse;
import com.ssafy.ddubi.domain.auth.dto.response.EmailChangeResponse;
import com.ssafy.ddubi.domain.auth.dto.response.OAuthLoginResponse;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.service.AuthService;
import com.ssafy.ddubi.domain.auth.service.EmailVerificationService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입 · 로그인 · 토큰 재발급 · 로그아웃")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
    }

    @Operation(summary = "이메일 인증 코드 발송",
            description = "회원가입 전 이메일 소유를 확인한다. 60초 안에 다시 요청하면 400. AUTH-006")
    @PostMapping("/email/verification")
    public ResponseEntity<Void> sendEmailVerification(
            @Valid @RequestBody EmailVerificationRequest request) {
        emailVerificationService.sendCode(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "이메일 인증 코드 확인",
            description = "확인에 성공하면 30분간 유효한 가입 자격이 생긴다. 5회 틀리면 코드가 폐기된다. AUTH-007")
    @PostMapping("/email/verification/confirm")
    public ResponseEntity<Void> confirmEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest request) {
        emailVerificationService.confirm(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "비밀번호 변경",
            description = """
                    로그인한 본인의 비밀번호를 바꾼다. 지금 쓰는 비밀번호를 함께 보낸다.

                    **바꾸면 다른 기기는 전부 로그아웃된다.** 비밀번호를 바꾸는 흔한 이유가
                    "누가 내 계정을 쓰는 것 같다" 인데, 세션을 두면 이미 들어와 있는 쪽은
                    계속 들어와 있다. 요청한 기기만 새 토큰을 받아 이어서 쓴다.

                    | 상황 | 응답 |
                    |---|---|
                    | 현재 비밀번호 불일치 | 401 `UNAUTHORIZED` |
                    | 새 비밀번호가 지금 것과 같음 | 400 `INVALID_REQUEST` |
                    | 소셜로만 가입한 계정 | 400 `INVALID_REQUEST` |

                    AUTH-008""")
    @PatchMapping("/password")
    public ApiResponse<AuthTokenResponse> changePassword(
            @LoginUserId Long userId,
            @Valid @RequestBody PasswordChangeRequest request) {
        return ApiResponse.success(authService.changePassword(userId, request));
    }

    @Operation(summary = "이메일 변경",
            description = """
                    로그인한 본인의 이메일을 바꾼다. **새 주소의 인증을 먼저 마쳐야 한다** —
                    `POST /auth/email/verification` → `.../confirm` 순서는 가입과 같다.

                    비밀번호가 있는 계정이면 비밀번호도 함께 보낸다. 소셜로만 가입한 계정은 비워 둔다.

                    토큰은 그대로 쓴다. 다음 로그인부터 새 이메일을 쓴다.

                    | 상황 | 응답 |
                    |---|---|
                    | 인증을 안 마친 주소 | 400 `INVALID_REQUEST` |
                    | 이미 쓰는 이메일 | 409 `DUPLICATE_RESOURCE` |
                    | 비밀번호 불일치 | 401 `UNAUTHORIZED` |

                    AUTH-009""")
    @PatchMapping("/email")
    public ApiResponse<EmailChangeResponse> changeEmail(
            @LoginUserId Long userId,
            @Valid @RequestBody EmailChangeRequest request) {
        return ApiResponse.success(authService.changeEmail(userId, request));
    }

    @Operation(summary = "로컬 회원가입",
            description = "user 와 user_auth(LOCAL) 를 함께 만든다. 이메일 인증을 먼저 마쳐야 한다. AUTH-001")
    @PostMapping("/local/signup")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> signupLocal(
            @Valid @RequestBody LocalSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.signupLocal(request)));
    }

    @Operation(summary = "로컬 로그인", description = "AUTH-002")
    @PostMapping("/local/login")
    public ApiResponse<AuthTokenResponse> loginLocal(
            @Valid @RequestBody LocalLoginRequest request) {
        return ApiResponse.success(authService.loginLocal(request));
    }

    /** 세 소셜 로그인이 공유하는 응답 규칙. 상태 코드가 세 갈래다. */
    private static final String OAUTH_DESCRIPTION = """
            응답 `data.status` 로 분기한다.

            | status | 언제 | HTTP |
            | --- | --- | --- |
            | `AUTHENTICATED` | 이미 연결된 소셜이거나 완전 신규 | 200 / 신규는 201 |
            | `ACCOUNT_LINK_REQUIRED` | 같은 검증 이메일의 기존 계정이 있다 | 200 |

            `ACCOUNT_LINK_REQUIRED` 는 **오류가 아니라 다음 단계**다. `linkToken`(5분·일회용)과
            마스킹된 이메일을 함께 주므로, 기존 계정 비밀번호를 받아 `/auth/oauth/link` 를 부른다.

            자동 통합은 하지 않는다(2026-09-10 변경). 이메일이 같다는 것만으로 계정을 넘기면
            제공자가 이메일 소유를 잘못 확인한 순간 남의 계정으로 들어가게 된다.

            기존 계정에 비밀번호가 없으면(소셜로만 가입) 409 `ACCOUNT_LINK_UNAVAILABLE` 이다 —
            `user.email` 이 UNIQUE 라 새 계정을 만들 수도 없어, 쓰던 소셜로 로그인하도록 안내한다.
            AUTH-003
            """;

    @Operation(summary = "소셜 로그인", description = "인가 코드를 받아 토큰을 교환한다.\n\n" + OAUTH_DESCRIPTION)
    @PostMapping("/oauth")
    public ResponseEntity<ApiResponse<OAuthLoginResponse>> loginOAuth(
            @Valid @RequestBody OAuthLoginRequest request) {
        return respond(authService.loginOAuth(request));
    }

    @Operation(summary = "모바일 소셜 로그인",
            description = "모바일 SDK가 발급한 액세스 토큰을 검증한다(카카오·네이버).\n\n" + OAUTH_DESCRIPTION)
    @PostMapping("/oauth/token")
    public ResponseEntity<ApiResponse<OAuthLoginResponse>> loginOAuthToken(
            @Valid @RequestBody OAuthAccessTokenLoginRequest request) {
        return respond(authService.loginOAuthToken(request));
    }

    @Operation(summary = "모바일 OIDC 로그인",
            description = "모바일 SDK가 발급한 ID 토큰을 검증한다(Google).\n\n" + OAUTH_DESCRIPTION)
    @PostMapping("/oauth/id-token")
    public ResponseEntity<ApiResponse<OAuthLoginResponse>> loginOAuthIdToken(
            @Valid @RequestBody OAuthIdTokenLoginRequest request) {
        return respond(authService.loginOAuthIdToken(request));
    }

    @Operation(summary = "계정 연동 확정",
            description = """
                    소셜 로그인이 `ACCOUNT_LINK_REQUIRED` 를 준 뒤에 부른다. 기존 이메일 계정의
                    비밀번호로 소유를 확인하고 소셜 인증 수단을 추가한 다음 토큰을 발급한다.

                    **소셜 액세스 토큰은 보내지 않는다.** 누구를 어디에 붙일지는 서버가
                    `linkToken` 으로 들고 있다.

                    `linkToken` 은 **성공·실패와 무관하게 한 번 쓰면 폐기**된다. 비밀번호가
                    틀렸다고 살려 두면 이 화면이 비밀번호 대입 창구가 된다 — 다시 하려면
                    소셜 로그인부터 거쳐야 한다.

                    **로그인 전에 부르는 API 다.** 이 시점에는 아직 토큰이 없다.

                    | 상황 | 응답 | 화면 |
                    | --- | --- | --- |
                    | 비밀번호 불일치 | 401 `LINK_PASSWORD_MISMATCH` | **소셜 로그인부터 다시** |
                    | 티켓 만료·재사용 | 401 `LINK_TOKEN_INVALID` | **소셜 로그인부터 다시** |
                    | 그 소셜이 다른 사용자에게 연결됨 | 409 `SOCIAL_ALREADY_LINKED` | |
                    | 기존 계정에 비밀번호가 없음 | 409 `ACCOUNT_LINK_UNAVAILABLE` | |

                    ⚠️ 비밀번호가 틀려도 **티켓은 이미 소비**됐다. 입력창만 비우고 재시도시키면
                    안 된다 — 그 티켓으로는 무엇을 넣어도 통하지 않는다. 그래서 일반 로그인
                    실패(`UNAUTHORIZED`)와 코드를 나눴다.
                    """)
    @PostMapping("/oauth/link")
    public ApiResponse<AuthTokenResponse> linkOAuthAccount(
            @Valid @RequestBody OAuthLinkRequest request) {
        return ApiResponse.success(authService.linkOAuthAccount(request));
    }

    @Operation(summary = "마이페이지 소셜 연결",
            description = """
                    **로그인한 상태에서** 소셜 계정을 추가로 연결한다. 소셜로만 가입해
                    `ACCOUNT_LINK_UNAVAILABLE` 안내를 받은 사용자가 오는 곳이다.

                    비밀번호를 묻지 않는다. 이미 그 계정으로 인증된 상태이고, 되물으면
                    **비밀번호가 없는 사용자**는 영영 연결하지 못한다 — 그들이 바로 이 화면의
                    대상이다.

                    KAKAO·NAVER 는 `accessToken`, GOOGLE 은 `idToken` 을 보낸다.
                    응답은 연결 후 그 계정의 **전체** 로그인 수단이다.

                    | 상황 | 응답 |
                    | --- | --- |
                    | 자격증명 없음 | 400 `INVALID_REQUEST` |
                    | 그 소셜이 다른 사용자에게 연결됨 | 409 `SOCIAL_ALREADY_LINKED` |
                    | 같은 제공자의 **다른** 계정이 이미 연결됨 | 409 `SOCIAL_PROVIDER_ALREADY_BOUND` |

                    이미 내게 연결된 소셜을 다시 보내면 오류가 아니라 200 이다(멱등).
                    """)
    @PostMapping("/social-links")
    public ApiResponse<SocialLinkResponse> linkSocial(
            @LoginUserId Long userId,
            @Valid @RequestBody SocialLinkRequest request) {
        return ApiResponse.success(authService.linkSocialToCurrentUser(userId, request));
    }

    @Operation(summary = "마이페이지 소셜 해제",
            description = """
                    연결된 소셜 인증 수단을 뗀다. 응답은 해제 후 **남은** 로그인 수단이다.

                    **마지막 하나는 뗄 수 없다.** 떼는 순간 사용자가 자기 계정에서 잠기고,
                    되돌릴 방법도 없다 — 들어갈 수가 없으니 다시 연결할 화면에도 못 간다.
                    계정을 없애려는 것이면 `DELETE /users/me`(탈퇴)가 맞는 길이다.

                    `LOCAL` 은 이 API 로 해제할 수 없다. 비밀번호 로그인을 없애는 것은 성격이
                    다른 일이라(다시 만들려면 이메일 인증부터 해야 한다) 같은 문으로 처리하지 않는다.

                    세션은 건드리지 않는다. 토큰은 사용자에게 발급된 것이지 제공자에게 발급된
                    것이 아니라, 카카오 연결을 뗐다고 쓰던 앱에서 튕겨 나갈 이유가 없다.

                    | 상황 | 응답 |
                    | --- | --- |
                    | 연결돼 있지 않음(다른 탭에서 이미 해제 포함) | 404 `SOCIAL_LINK_NOT_FOUND` |
                    | `LOCAL` 을 지정 | 400 `INVALID_REQUEST` |
                    | 마지막 남은 수단 | 409 `LAST_LOGIN_METHOD` |

                    동시 요청은 행 잠금으로 직렬화한다. 서로 다른 소셜을 동시에 해제해도
                    **하나는 반드시 남는다** — 뒤에 온 요청은 다시 읽고 409 를 받는다.

                    ⚠️ 화면에 수단이 둘로 보이는데 `LAST_LOGIN_METHOD` 가 올 수 있다.
                    다른 기기에서 방금 하나가 빠진 경우다. 목록을 다시 받아 화면을 갱신한다.
                    """)
    @DeleteMapping("/social-links/{provider}")
    public ApiResponse<SocialLinkResponse> unlinkSocial(
            @LoginUserId Long userId,
            @PathVariable AuthProvider provider) {
        return ApiResponse.success(authService.unlinkSocialFromCurrentUser(userId, provider));
    }

    /** 신규 가입만 201. 연동 요구는 실패가 아니므로 200 이다. */
    private ResponseEntity<ApiResponse<OAuthLoginResponse>> respond(AuthService.OAuthResult result) {
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result.response()));
    }

    @Operation(summary = "토큰 재발급",
            description = "리프레시 토큰을 회전시킨다. 이미 회전된 토큰이 오면 탈취로 보고 "
                    + "해당 사용자의 모든 기기 세션을 폐기한다. AUTH-004")
    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(
            @Valid @RequestBody TokenRefreshRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    @Operation(summary = "로그아웃",
            description = "해당 기기 세션을 폐기한다. fcmToken 을 주면 그 기기 푸시도 중단한다. AUTH-005")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@LoginUserId Long userId,
                                       @Valid @RequestBody LogoutRequest request) {
        authService.logout(userId, request);
        return ResponseEntity.noContent().build();
    }
}
