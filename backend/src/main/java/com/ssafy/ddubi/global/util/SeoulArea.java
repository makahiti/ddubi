package com.ssafy.ddubi.global.util;

import java.math.BigDecimal;

/**
 * 서비스 지역(서울) 판정 — 경계 상자 근사.
 *
 * 뚜비는 서울 한정 서비스다. segment_id 채번·기준선·실시간 상태가 전부 서울
 * 대중교통 데이터를 전제하므로, 서울 밖 좌표를 받으면 이후 단계 전체가 조용히
 * 틀린 값을 낸다. 입구(장소 등록·일정 등록)에서 422 OUT_OF_SERVICE_AREA 로 끊는다.
 *
 * 행정 경계 폴리곤 대신 경계 상자를 쓰는 이유: 판정 목적이 "명백한 서울 밖"을
 * 걸러내는 것이지 경계선 위 정밀 판정이 아니다. 과천·광명 일부가 통과될 수 있으나
 * 그 좌표의 대중교통 데이터가 없으면 추천 단계에서 폴백으로 처리된다.
 */
public final class SeoulArea {

    /** 서울 외곽 경계 근사값 (WGS84). */
    static final double MIN_LAT = 37.41;
    static final double MAX_LAT = 37.72;
    static final double MIN_LNG = 126.73;
    static final double MAX_LNG = 127.19;

    private SeoulArea() {
    }

    public static boolean contains(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null) {
            return false;
        }
        return contains(lat.doubleValue(), lng.doubleValue());
    }

    public static boolean contains(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    /** 카카오 로컬 검색의 rect 파라미터 형식: 좌X,좌Y,우X,우Y (경도,위도 순서 주의). */
    public static String kakaoRect() {
        return MIN_LNG + "," + MIN_LAT + "," + MAX_LNG + "," + MAX_LAT;
    }
}
