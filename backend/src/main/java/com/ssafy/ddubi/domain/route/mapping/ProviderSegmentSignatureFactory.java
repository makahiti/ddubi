package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** 지하철 이름·전체 역 순서의 길이 접두 문자열을 SHA-256 캐시 서명으로 만든다.
 * 버스는 원문 차량명과 전체 공간 증거를 별도 서명하므로 이 클래스가 처리하지 않는다.
 * 마스터·별칭 정책은 DB 복합키에서 별도 구분한다. 외부 I/O와 상태는 없다. */
@Component
public class ProviderSegmentSignatureFactory {

    /**
     * Matcher 결과에 영향을 주는 provider 증거만 canonical 문자열로 만들고 SHA-256을 반환한다.
     * 결과는 DB {@code provider_signature CHAR(64)}에 저장되며 마스터·정책 버전과 함께 캐시 키가 된다.
     */
    public String create(SegmentResolveRequest request, TransitNameNormalizer normalizer) {
        if(request.mode()!=TransportMode.SUBWAY)throw new IllegalArgumentException("지하철 전용 캐시 서명입니다.");
        // 필드마다 길이를 먼저 기록하므로 값 안에 ':'이나 '#'이 있어도 서로 다른 입력이 같은 문자열이 되지 않는다.
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.mode().name());
        appendList(canonical, request.vehicleNames().stream()
                .map(normalizer::normalizeSubwayLineName)
                .toList());
        append(canonical, normalizer.normalizeSubwayStationName(request.fromName()));
        append(canonical, normalizer.normalizeSubwayStationName(request.toName()));
        appendList(canonical, request.stopNames().stream()
                .map(normalizer::normalizeSubwayStationName)
                .toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM에 SHA-256 구현이 없습니다.", exception);
        }
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
