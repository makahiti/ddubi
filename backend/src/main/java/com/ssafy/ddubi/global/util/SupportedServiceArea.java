package com.ssafy.ddubi.global.util;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 뚜비가 경로 후보를 조회하고 저장할 수 있는 서비스 권역을 판정한다.
 *
 * <p>현재 정적 버스 마스터가 있는 수도권과, 공식 마스터가 준비되지 않았을 때도 카카오 원본 시간으로 안전하게 폴백할 수 있는 부산을 허용한다. 이 판정은 계산식 적용 가능
 * 여부를 뜻하지 않는다. 해당 지역의 마스터 또는 기준값이 없으면 추천 계층이 기존 카카오 시간을 유지하므로, 서비스 지역 확대가 임의 구간 ID 생성이나 미검증 계산식
 * 적용으로 이어지지 않는다.
 *
 * <p>행정 경계의 정밀 판정보다 명백한 서비스 외 좌표를 조기에 거르는 것이 목적이므로 WGS84 경계 상자를 사용한다. 카카오 장소 검색도 같은 목록을 사용하여 등록 단계와
 * 검색 단계의 허용 범위가 달라지지 않게 한다.
 */
public final class SupportedServiceArea {

    /** 검색 제공자에 전달할 독립 권역이다. 수도권과 부산 사이의 미지원 지역이 검색 범위에 포함되지 않도록 두 사각형을 따로 유지한다. */
    public record Area(String code, double minLat, double maxLat, double minLng, double maxLng) {
        public boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }

        /** 카카오 rect 형식은 경도,위도 순서다. */
        public String kakaoRect() {
            return minLng + "," + minLat + "," + maxLng + "," + maxLat;
        }
    }

    /** 서울·경기·인천을 포함하는 수도권 운영 경계 근사값. */
    public static final Area CAPITAL = new Area("CAPITAL", 36.85, 38.30, 125.80, 127.90);

    /** 부산광역시 운영 경계 근사값. */
    public static final Area BUSAN = new Area("BUSAN", 34.80, 35.45, 128.70, 129.40);

    private static final List<Area> AREAS = List.of(CAPITAL, BUSAN);

    /**
     * 카카오 주소의 시도 표기. 짧은 형태와 정식 명칭을 모두 받는다. 경계 상자는 서비스 밖 지역(강원 서부 등)을 걸러내지 못하므로, 주소를 아는 경우에는 시도 이름으로
     * 한 번 더 판정한다.
     */
    private static final Set<String> SERVICE_REGIONS =
            Set.of("서울", "서울특별시", "경기", "경기도", "인천", "인천광역시", "부산", "부산광역시");

    /** 서비스 밖인 것이 분명한 시도 표기. 여기에 없는 낯선 표기는 거절하지 않는다. */
    private static final Set<String> KNOWN_OUTSIDE_REGIONS =
            Set.of(
                    "강원", "강원도", "강원특별자치도", "충북", "충청북도", "충남", "충청남도", "대전", "대전광역시", "세종",
                    "세종특별자치시", "전북", "전라북도", "전북특별자치도", "전남", "전라남도", "광주", "광주광역시", "경북", "경상북도",
                    "경남", "경상남도", "대구", "대구광역시", "울산", "울산광역시", "제주", "제주도", "제주특별자치도");

    private SupportedServiceArea() {}

    public static boolean contains(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null) {
            return false;
        }
        return contains(lat.doubleValue(), lng.doubleValue());
    }

    public static boolean contains(double lat, double lng) {
        return Double.isFinite(lat)
                && Double.isFinite(lng)
                && AREAS.stream().anyMatch(area -> area.contains(lat, lng));
    }

    /** 호출자가 목록을 바꾸지 못하도록 불변 권역 목록을 반환한다. */
    public static List<Area> areas() {
        return AREAS;
    }

    /**
     * 주소가 서비스 밖인 것이 분명한지 판정한다.
     *
     * <p>경계 상자는 사각형이라 수도권 상자에 강원 서부가 함께 들어온다. 주소를 아는 경우에는 시도 이름으로 걸러낼 수 있다. 알 수 없는 표기는 거절하지 않는다. 표기
     * 변화나 해외 주소 때문에 정상 장소가 사라지는 편이 미지원 지역 한 건이 보이는 것보다 나쁘기 때문이다.
     */
    public static boolean addressClearlyOutside(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String region = address.trim().split("\s+")[0];
        return !SERVICE_REGIONS.contains(region) && KNOWN_OUTSIDE_REGIONS.contains(region);
    }
}
