package com.ssafy.ddubi.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.ddubi.domain.place.dto.request.PlaceCreateRequest;
import com.ssafy.ddubi.domain.place.dto.request.PlaceUpdateRequest;
import com.ssafy.ddubi.domain.place.dto.response.PlaceResponse;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** 장소 CRUD (PLACE-002~006) — 도메인 규칙 1(소유권)과 서비스 권역 제한을 고정한다. */
class PlaceIntegrationTest extends IntegrationTestSupport {

    private static final BigDecimal SEOUL_LAT = new BigDecimal("37.5013068");
    private static final BigDecimal SEOUL_LNG = new BigDecimal("127.0396597");

    @Autowired private PlaceService placeService;
    @Autowired private UserRepository userRepository;

    private Long ownerId;
    private Long strangerId;

    @BeforeEach
    void setUpUsers() {
        ownerId =
                userRepository
                        .save(User.builder().email("owner@ddubi.com").nickname("주인").build())
                        .getId();
        strangerId =
                userRepository
                        .save(User.builder().email("other@ddubi.com").nickname("남").build())
                        .getId();
    }

    private PlaceResponse createDefault() {
        return placeService.create(
                ownerId, new PlaceCreateRequest("회사", "서울 강남구 테헤란로 212", SEOUL_LAT, SEOUL_LNG));
    }

    @Test
    @DisplayName("등록한 장소를 목록·상세로 되찾을 수 있다")
    void createAndRead() {
        PlaceResponse created = createDefault();

        assertThat(placeService.get(ownerId, created.id()).name()).isEqualTo("회사");
        assertThat(placeService.list(ownerId, PageRequest.of(0, 20)).totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("부산 좌표는 허용하고 미지원 지역은 422 OUT_OF_SERVICE_AREA")
    void acceptsBusanAndRejectsUnsupportedArea() {
        assertThat(
                        placeService
                                .create(
                                        ownerId,
                                        new PlaceCreateRequest(
                                                "부산집",
                                                "부산 해운대구",
                                                new BigDecimal("35.1587"),
                                                new BigDecimal("129.1604")))
                                .id())
                .isNotNull();

        assertThatThrownBy(
                        () ->
                                placeService.create(
                                        ownerId,
                                        new PlaceCreateRequest(
                                                "대전집",
                                                "대전 동구",
                                                new BigDecimal("36.3321"),
                                                new BigDecimal("127.4340"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_SERVICE_AREA);
    }

    @Test
    @DisplayName("남의 장소는 조회·수정·삭제 모두 403 이다 (도메인 규칙 1)")
    void ownershipIsEnforced() {
        Long placeId = createDefault().id();
        PlaceUpdateRequest update = new PlaceUpdateRequest();
        update.setName("탈취");

        assertThatThrownBy(() -> placeService.get(strangerId, placeId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
        assertThatThrownBy(() -> placeService.update(strangerId, placeId, update))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
        assertThatThrownBy(() -> placeService.delete(strangerId, placeId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
    }

    @Test
    @DisplayName("보낸 필드만 바뀐다 — 이름만 수정하면 좌표는 그대로다")
    void partialUpdate() {
        Long placeId = createDefault().id();
        PlaceUpdateRequest update = new PlaceUpdateRequest();
        update.setName("새 회사");

        PlaceResponse updated = placeService.update(ownerId, placeId, update);

        assertThat(updated.name()).isEqualTo("새 회사");
        assertThat(updated.lat()).isEqualByComparingTo(SEOUL_LAT);
        assertThat(updated.address()).isEqualTo("서울 강남구 테헤란로 212");
    }

    @Test
    @DisplayName("좌표는 쌍으로만 수정할 수 있다 — 위도만 보내면 400")
    void coordinatesMustComeInPairs() {
        Long placeId = createDefault().id();
        PlaceUpdateRequest update = new PlaceUpdateRequest();
        update.setLat(new BigDecimal("37.55"));

        assertThatThrownBy(() -> placeService.update(ownerId, placeId, update))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("좌표 수정도 지원 권역 검증을 거친다")
    void coordinateUpdateChecksSupportedServiceArea() {
        Long placeId = createDefault().id();
        PlaceUpdateRequest update = new PlaceUpdateRequest();
        // 부산은 지원 권역에 포함되므로, 지원하지 않는 대전 좌표로 실제 거부 경계를 검증한다.
        update.setLat(new BigDecimal("36.3504"));
        update.setLng(new BigDecimal("127.3845"));

        assertThatThrownBy(() -> placeService.update(ownerId, placeId, update))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_SERVICE_AREA);
    }

    @Test
    @DisplayName("삭제하면 404 로 조회된다")
    void delete() {
        Long placeId = createDefault().id();

        placeService.delete(ownerId, placeId);

        assertThatThrownBy(() -> placeService.get(ownerId, placeId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PLACE_NOT_FOUND);
    }
}
