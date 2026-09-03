package com.ssafy.ddubi.domain.route.mapping;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * 외부 제공자와 공식 마스터의 표기 차이를 후보 검색용 문자열로 정규화한다.
 *
 * <p>정규화는 후보를 좁히는 수단일 뿐 같은 공식 ID라는 증명이 아니다. 최종 확정은
 * {@link BusSegmentMatcher}와 {@link SubwaySegmentMatcher}가 노선, 승·하차 순서,
 * 중간 정류장·역과 방향을 다시 검사한다.</p>
 *
 * <p>이 클래스는 의도적으로 별칭을 적용하지 않는다. 공식 마스터의 이름까지 외부 제공자
 * 별칭으로 다시 쓰면 서로 다른 공식 노선·정류장이 하나의 검색 키로 합쳐질 수 있기 때문이다.
 * 검증된 별칭은 Matcher가 {@code alias -> canonical ID}로 직접 해석한다.</p>
 */
public class TransitNameNormalizer {

    public String normalizeRouteName(String value) {
        return normalizeRouteBase(value);
    }

    public String normalizeStopName(String value) {
        // '역'은 실제 역명의 일부이므로 제거하지 않고, 의미 없는 '정류장' 접미사만 정리한다.
        return normalizeStopBase(value);
    }

    /**
     * 지하철 호선명을 비교 가능한 형태로 만든다.
     * 카카오의 {@code 수도권 02호선}과 공식 마스터의 {@code 2호선}처럼 제공자 장식어와
     * 숫자 앞의 0만 다른 경우를 같은 후보로 찾는다. 경의중앙선 같은 고유 노선명은 보존한다.
     */
    public String normalizeSubwayLineName(String value) {
        return normalizeSubwayLineBase(value);
    }

    private String normalizeSubwayLineBase(String value) {
        String normalized = normalizeRouteBase(value);
        for (String prefix : new String[]{"수도권전철", "서울지하철", "지하철", "수도권"}) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
                break;
            }
        }
        if (normalized.matches("0+[0-9]+호선")) {
            normalized = normalized.replaceFirst("^0+", "");
        }
        return normalized;
    }

    /**
     * 지하철 역명을 비교 가능한 형태로 만든다.
     * 공식 데이터의 {@code 강남}과 카카오의 {@code 강남역}을 연결하기 위해 마지막 '역'만 제거한다.
     * {@code 역촌}처럼 이름 앞이나 중간에 있는 글자는 제거하지 않는다.
     */
    public String normalizeSubwayStationName(String value) {
        return normalizeSubwayStationBase(value);
    }

    private String normalizeSubwayStationBase(String value) {
        String normalized = normalizeStopBase(value);
        if (normalized.endsWith("역")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeRouteBase(String value) {
        String normalized = normalizeCommon(value, true);
        // '422번', '422버스'처럼 제공자별 장식어만 제거한다. 실제 노선 번호 본문은 보존한다.
        if (normalized.endsWith("버스")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.endsWith("번")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeStopBase(String value) {
        String normalized = normalizeCommon(value, false);
        if (normalized.endsWith("정류장")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private String normalizeCommon(String value, boolean keepHyphen) {
        // NFKC로 전각 숫자·문자 등을 통일한 뒤 괄호 부가설명과 검색에 불필요한 구두점을 제거한다.
        String source = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^]]*]", "")
                .toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(source.length());
        source.codePoints().forEach(codePoint -> {
            // 버스 노선의 119-1과 1191은 다른 노선이므로 노선명에서는 하이픈을 반드시 보존한다.
            if (Character.isLetterOrDigit(codePoint) || (keepHyphen && codePoint == '-')) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }
}
