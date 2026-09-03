package com.ssafy.ddubi.domain.auth.controller;

import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.ssafy.ddubi.domain.auth.dto.request.EmailVerificationRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LogoutRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthAccessTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.TokenRefreshRequest;
import com.ssafy.ddubi.domain.auth.dto.response.AuthTokenResponse;
import com.ssafy.ddubi.domain.auth.service.AuthService;
import com.ssafy.ddubi.domain.auth.service.EmailVerificationService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @Operation(summary = "소셜 로그인",
            description = "인가 코드를 받아 토큰을 교환한다. 같은 이메일의 기존 계정이 있으면 "
                    + "인증 수단만 추가하고 200, 완전 신규면 201. AUTH-003")
    @PostMapping("/oauth")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> loginOAuth(
            @Valid @RequestBody OAuthLoginRequest request) {
        AuthService.OAuthResult result = authService.loginOAuth(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result.tokens()));
    }

    @Operation(summary = "모바일 소셜 로그인",
            description = "모바일 SDK가 발급한 액세스 토큰을 검증한다. 현재 카카오만 지원한다. "
                    + "기존 계정이면 200, 완전 신규면 201. AUTH-003")
    @PostMapping("/oauth/token")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> loginOAuthToken(
            @Valid @RequestBody OAuthAccessTokenLoginRequest request) {
        AuthService.OAuthResult result = authService.loginOAuthToken(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result.tokens()));
    }

    @Operation(summary = "모바일 OIDC 로그인",
            description = "모바일 SDK가 발급한 ID 토큰을 검증한다. 현재 Google만 지원한다. "
                    + "기존 계정이면 200, 완전 신규면 201. AUTH-003")
    @PostMapping("/oauth/id-token")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> loginOAuthIdToken(
            @Valid @RequestBody OAuthIdTokenLoginRequest request) {
        AuthService.OAuthResult result = authService.loginOAuthIdToken(request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result.tokens()));
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
