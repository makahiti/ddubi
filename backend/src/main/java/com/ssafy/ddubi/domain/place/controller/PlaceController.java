package com.ssafy.ddubi.domain.place.controller;

import com.ssafy.ddubi.domain.place.dto.request.PlaceCreateRequest;
import com.ssafy.ddubi.domain.place.dto.request.PlaceUpdateRequest;
import com.ssafy.ddubi.domain.place.dto.response.PlaceResponse;
import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.domain.place.service.PlaceService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.common.PageRequestParam;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "장소", description = "수도권·부산 장소 검색 · 즐겨찾기 CRUD")
@RestController
@RequestMapping("/api/v1/places")
@Validated
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @Operation(
            summary = "수도권·부산 장소 검색",
            description = "카카오 로컬 검색 프록시. 수도권·부산 밖 결과는 제외된다. PLACE-001")
    @GetMapping("/search")
    public ApiResponse<PageResponse<PlaceSearchItem>> search(
            @RequestParam @NotBlank(message = "query 는 필수입니다.") String query,
            @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(
                placeService.search(query, pageParam.getPage(), pageParam.getSize()));
    }

    @Operation(summary = "즐겨찾는 장소 목록", description = "PLACE-002")
    @GetMapping
    public ApiResponse<PageResponse<PlaceResponse>> list(
            @LoginUserId Long userId, @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(
                placeService.list(
                        userId, pageParam.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @Operation(summary = "장소 등록", description = "수도권·부산 밖 좌표는 422. PLACE-003")
    @PostMapping
    public ResponseEntity<ApiResponse<PlaceResponse>> create(
            @LoginUserId Long userId, @Valid @RequestBody PlaceCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(placeService.create(userId, request)));
    }

    @Operation(summary = "장소 상세", description = "PLACE-004")
    @GetMapping("/{placeId}")
    public ApiResponse<PlaceResponse> get(@LoginUserId Long userId, @PathVariable Long placeId) {
        return ApiResponse.success(placeService.get(userId, placeId));
    }

    @Operation(summary = "장소 수정", description = "보낸 필드만 바뀐다. 좌표는 lat·lng 쌍으로. PLACE-005")
    @PatchMapping("/{placeId}")
    public ApiResponse<PlaceResponse> update(
            @LoginUserId Long userId,
            @PathVariable Long placeId,
            @Valid @RequestBody PlaceUpdateRequest request) {
        return ApiResponse.success(placeService.update(userId, placeId, request));
    }

    @Operation(summary = "장소 삭제", description = "기존 일정은 좌표 스냅샷이라 영향 없다(도메인 규칙 2). PLACE-006")
    @DeleteMapping("/{placeId}")
    public ResponseEntity<Void> delete(@LoginUserId Long userId, @PathVariable Long placeId) {
        placeService.delete(userId, placeId);
        return ResponseEntity.noContent().build();
    }
}
