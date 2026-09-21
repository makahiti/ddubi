package com.ssafy.ddubi.domain.place.service;

import com.ssafy.ddubi.domain.place.client.KakaoLocalClient;
import com.ssafy.ddubi.domain.place.dto.request.PlaceCreateRequest;
import com.ssafy.ddubi.domain.place.dto.request.PlaceUpdateRequest;
import com.ssafy.ddubi.domain.place.dto.response.PlaceResponse;
import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.domain.place.entity.Place;
import com.ssafy.ddubi.domain.place.repository.PlaceRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.SupportedServiceArea;
import java.math.BigDecimal;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장소 (PLACE-001~006).
 *
 * <p>이 클래스가 지키는 도메인 규칙: - 규칙 1 소유권: 모든 조회·변경 전 user_id 를 검사한다. 남의 장소는 403 - 서비스 권역 제한: 수도권·부산 밖 좌표는
 * 422 OUT_OF_SERVICE_AREA - 규칙 2 스냅샷: 일정이 장소를 FK 참조하지 않으므로 삭제는 자유롭다 — 이 장소로 만든 기존 일정에는 아무 영향이 없다
 */
@Service
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final KakaoLocalClient kakaoLocalClient;

    public PlaceService(
            PlaceRepository placeRepository,
            UserRepository userRepository,
            KakaoLocalClient kakaoLocalClient) {
        this.placeRepository = placeRepository;
        this.userRepository = userRepository;
        this.kakaoLocalClient = kakaoLocalClient;
    }

    // ------------------------------------------------------------------
    // PLACE-001 서울 장소 검색 (카카오 프록시 — DB 를 거치지 않는다)
    // ------------------------------------------------------------------

    public PageResponse<PlaceSearchItem> search(String query, int page, int size) {
        return kakaoLocalClient.search(query, page, size);
    }

    // ------------------------------------------------------------------
    // PLACE-002 목록 / PLACE-004 상세
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PlaceResponse> list(Long userId, Pageable pageable) {
        return PageResponse.of(
                placeRepository.findAllByUserId(userId, pageable), PlaceResponse::from);
    }

    @Transactional(readOnly = true)
    public PlaceResponse get(Long userId, Long placeId) {
        return PlaceResponse.from(findOwned(userId, placeId));
    }

    // ------------------------------------------------------------------
    // PLACE-003 등록
    // ------------------------------------------------------------------

    @Transactional
    public PlaceResponse create(Long userId, PlaceCreateRequest request) {
        requireInServiceArea(request.lat(), request.lng(), request.address());
        User user = userRepository.getReferenceById(userId);
        Place place =
                placeRepository.save(
                        Place.builder()
                                .user(user)
                                .name(request.name())
                                .address(request.address())
                                .lat(request.lat())
                                .lng(request.lng())
                                .build());
        return PlaceResponse.from(place);
    }

    // ------------------------------------------------------------------
    // PLACE-005 수정 / PLACE-006 삭제
    // ------------------------------------------------------------------

    @Transactional
    public PlaceResponse update(Long userId, Long placeId, PlaceUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 필드가 없습니다.");
        }
        // 좌표는 쌍으로만 받는다 — 위도만 바뀌면 장소가 엉뚱한 곳으로 반쪽 이동한다.
        if (request.isLatPresent() != request.isLngPresent()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "lat 과 lng 는 함께 보내야 합니다.");
        }

        Place place = findOwned(userId, placeId);
        if (request.isNamePresent()) {
            requireText(request.getName(), "장소 이름");
            place.changeName(request.getName());
        }
        if (request.isAddressPresent()) {
            requireText(request.getAddress(), "주소");
            place.changeAddress(request.getAddress());
        }
        if (request.isLatPresent()) {
            if (request.getLat() == null || request.getLng() == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "좌표는 null 일 수 없습니다.");
            }
            requireInServiceArea(request.getLat(), request.getLng(), request.getAddress());
            place.changeCoordinate(request.getLat(), request.getLng());
        }
        return PlaceResponse.from(place);
    }

    @Transactional
    public void delete(Long userId, Long placeId) {
        // 규칙 2: 일정은 좌표를 스냅샷으로 들고 있어 이 삭제의 영향을 받지 않는다.
        placeRepository.delete(findOwned(userId, placeId));
    }

    // ------------------------------------------------------------------

    private Place findOwned(Long userId, Long placeId) {
        Place place =
                placeRepository
                        .findById(placeId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_NOT_FOUND));
        if (!place.isOwnedBy(userId)) {
            // 존재 여부를 숨기지 않는 이유: placeId 는 순번이라 추측 가능해 숨겨도 이득이 없고,
            // 명세서가 403 FORBIDDEN_RESOURCE 를 별도 정의한다(규칙 1).
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return place;
    }

    /**
     * 좌표 경계 상자로 먼저 거르고, 주소를 아는 경우 시도 이름으로 한 번 더 거른다. 수도권 상자는 사각형이라 강원 서부 등이 함께 들어오는데, 검색 화면이 이미
     * 걸러내는 지역을 등록 단계가 받아주면 두 단계의 허용 범위가 달라진다. 낯선 주소 표기는 거절하지 않는다.
     */
    private void requireInServiceArea(BigDecimal lat, BigDecimal lng, String address) {
        if (!SupportedServiceArea.contains(lat, lng)
                || SupportedServiceArea.addressClearlyOutside(address)) {
            throw new BusinessException(ErrorCode.OUT_OF_SERVICE_AREA, "수도권과 부산 지역만 서비스합니다.");
        }
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, label + "은(는) 비울 수 없습니다.");
        }
    }
}
