package com.ssafy.ddubi.domain.user.controller;

import com.ssafy.ddubi.domain.user.dto.request.UserUpdateRequest;
import com.ssafy.ddubi.domain.user.dto.request.UserWithdrawRequest;
import com.ssafy.ddubi.domain.user.dto.response.UserResponse;
import com.ssafy.ddubi.domain.user.service.UserService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자", description = "내 정보 조회·변경·탈퇴")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 정보 조회", description = "USER-001")
    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(@LoginUserId Long userId) {
        return ApiResponse.success(userService.getMe(userId));
    }

    @Operation(summary = "내 정보 변경",
            description = "변경할 필드만 보낸다. 보내지 않은 필드는 그대로 둔다. USER-002")
    @PatchMapping("/me")
    public ApiResponse<UserResponse> update(@LoginUserId Long userId,
                                            @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.success(userService.update(userId, request));
    }

    @Operation(summary = "회원 탈퇴",
            description = "되돌릴 수 없다. confirmation 에 WITHDRAW 를 보내야 하고, "
                    + "LOCAL 회원은 비밀번호도 확인한다. 하위 데이터는 CASCADE 로 함께 삭제된다. USER-003")
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@LoginUserId Long userId,
                                         @Valid @RequestBody UserWithdrawRequest request) {
        userService.withdraw(userId, request);
        return ResponseEntity.noContent().build();
    }
}
