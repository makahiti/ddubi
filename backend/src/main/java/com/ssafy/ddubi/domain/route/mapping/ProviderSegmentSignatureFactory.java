package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 카카오 구간의 이름 기반 입력을 매핑 캐시 조회용 SHA-256 서명으로 변환한다.
 *
 * <p>서명에는 교통수단, 모든 차량명, 승·하차명과 전체 통과 순서를 길이 접두 형식으로
 * 넣는다. BUS 결과는 path 양 끝 좌표에도 의존하므로 첫·끝 좌표를 7자리 소수 정밀도로
 * 양자화해 함께 넣는다. 좌표를 빼면 정상 path의 EXACT 캐시가 같은 이름의 원거리·역방향
 * 요청에 재사용될 수 있다. 단순 구분자 join과 달리 길이 접두 형식이라 값 안에 구분자가
 * 들어와도 충돌하지 않는다. 마스터·정책 버전은 DB 복합 UNIQUE 키의 별도 열로 구분한다.</p>
 */
@Component
public class ProviderSegmentSignatureFactory {

    /**
     * Matcher 결과에 영향을 주는 provider 증거만 canonical 문자열로 만들고 SHA-256을 반환한다.
     * 결과는 DB {@code provider_signature CHAR(64)}에 저장되며 마스터·정책 버전과 함께 캐시 키가 된다.
     */
    public String create(SegmentResolveRequest request, TransitNameNormalizer normalizer) {
        // 필드마다 길이를 먼저 기록하므로 값 안에 ':'이나 '#'이 있어도 서로 다른 입력이 같은 문자열이 되지 않는다.
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.mode().name());
        appendList(canonical, request.vehicleNames().stream()
                .map(name -> request.mode() == TransportMode.SUBWAY
                        ? normalizer.normalizeSubwayLineName(name)
                        : normalizer.normalizeRouteName(name))
                .toList());
        append(canonical, normalizeNode(request.fromName(), request, normalizer));
        append(canonical, normalizeNode(request.toName(), request, normalizer));
        appendList(canonical, request.stopNames().stream()
                .map(name -> normalizeNode(name, request, normalizer))
                .toList());
        // 지하철 판정은 geometry를 사용하지 않는다. BUS만 실제 판정 입력인 첫·끝 좌표를 포함한다.
        if (request.mode() == TransportMode.BUS) {
            appendBusGeometry(canonical, request.geometry());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM에 SHA-256 구현이 없습니다.", exception);
        }
    }

    /** Matcher가 실제 판정에 쓰는 첫·끝 좌표만 같은 정밀도로 서명한다. */
    private void appendBusGeometry(
            StringBuilder target,
            List<com.ssafy.ddubi.domain.route.model.Coordinate> geometry
    ) {
        if (geometry == null || geometry.size() < 2) {
            append(target, "NO_ENDPOINT_GEOMETRY");
            return;
        }
        com.ssafy.ddubi.domain.route.model.Coordinate first = geometry.get(0);
        com.ssafy.ddubi.domain.route.model.Coordinate last = geometry.get(geometry.size() - 1);
        append(target, coordinate(first.longitude()));
        append(target, coordinate(first.latitude()));
        append(target, coordinate(last.longitude()));
        append(target, coordinate(last.latitude()));
    }

    /** 약 1cm 수준인 소수 7자리로 고정해 JSON 역직렬화의 미세 표현 차이만 제거한다. */
    private String coordinate(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return BigDecimal.valueOf(value)
                .setScale(7, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String normalizeNode(
            String value,
            SegmentResolveRequest request,
            TransitNameNormalizer normalizer
    ) {
        return request.mode() == TransportMode.SUBWAY
                ? normalizer.normalizeSubwayStationName(value)
                : normalizer.normalizeStopName(value);
    }

    private void appendList(StringBuilder target, List<String> values) {
        target.append(values.size()).append('#');
        values.forEach(value -> append(target, value));
    }

    private void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe);
    }
}
