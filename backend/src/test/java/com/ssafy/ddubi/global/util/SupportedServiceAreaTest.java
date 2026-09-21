package com.ssafy.ddubi.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 서비스 권역 변경이 계산식 유무와 무관하게 진입 단계에서 회귀하지 않는지 검증한다. */
class SupportedServiceAreaTest {

    @Test
    void acceptsCapitalAreaAndBusanButRejectsUnsupportedRegion() {
        assertThat(SupportedServiceArea.contains(37.5665, 126.9780)).isTrue(); // 서울
        assertThat(SupportedServiceArea.contains(37.2636, 127.0286)).isTrue(); // 경기
        assertThat(SupportedServiceArea.contains(37.4563, 126.7052)).isTrue(); // 인천
        assertThat(SupportedServiceArea.contains(35.1796, 129.0756)).isTrue(); // 부산
        assertThat(SupportedServiceArea.contains(36.3504, 127.3845)).isFalse(); // 대전
    }

    @Test
    void rejectsNullAndNonFiniteCoordinates() {
        assertThat(SupportedServiceArea.contains(null, BigDecimal.ONE)).isFalse();
        assertThat(SupportedServiceArea.contains(Double.NaN, 127.0)).isFalse();
        assertThat(SupportedServiceArea.contains(37.5, Double.POSITIVE_INFINITY)).isFalse();
    }

    @Test
    void rectangleLetsUnsupportedRegionsInSoAddressDecidesToo() {
        // 수도권 사각형은 강원 서부를 함께 덮는다. 좌표만으로는 춘천을 거를 수 없다.
        assertThat(SupportedServiceArea.contains(37.8813, 127.7298)).isTrue(); // 춘천
        assertThat(SupportedServiceArea.addressClearlyOutside("강원특별자치도 춘천시 시청길 11")).isTrue();
        assertThat(SupportedServiceArea.addressClearlyOutside("강원 춘천시 옥천동")).isTrue();
    }

    @Test
    void keepsServiceRegionAddresses() {
        assertThat(SupportedServiceArea.addressClearlyOutside("서울 중구 세종대로 110")).isFalse();
        assertThat(SupportedServiceArea.addressClearlyOutside("경기도 성남시 분당구")).isFalse();
        assertThat(SupportedServiceArea.addressClearlyOutside("인천광역시 연수구")).isFalse();
        assertThat(SupportedServiceArea.addressClearlyOutside("부산 해운대구 구남로 1")).isFalse();
    }

    @Test
    void unknownAddressFormsAreNotRejected() {
        // 표기 변화나 지번만 있는 주소 때문에 정상 장소가 사라지면 안 된다. 모르면 통과시킨다.
        assertThat(SupportedServiceArea.addressClearlyOutside("성동구 성수동1가")).isFalse();
        assertThat(SupportedServiceArea.addressClearlyOutside("")).isFalse();
        assertThat(SupportedServiceArea.addressClearlyOutside(null)).isFalse();
    }

    @Test
    void exposesSeparateSearchRectangles() {
        assertThat(SupportedServiceArea.areas())
                .extracting(SupportedServiceArea.Area::code)
                .containsExactly("CAPITAL", "BUSAN");
        assertThat(SupportedServiceArea.areas())
                .extracting(SupportedServiceArea.Area::kakaoRect)
                .containsExactly("125.8,36.85,127.9,38.3", "128.7,34.8,129.4,35.45");
    }
}
