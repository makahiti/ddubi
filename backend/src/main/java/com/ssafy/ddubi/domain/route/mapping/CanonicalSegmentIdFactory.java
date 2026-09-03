package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 검증된 공식 인접 노드 쌍을 파이프라인 공용 {@code segment_id}로 직렬화한다.
 *
 * <p>입력은 서울 공식 노선/호선 ID와 실제로 이웃한 두 노드 ID이며 이름은 사용하지 않는다.
 * 출력 형식은 {@code BUS:{routeId}:{fromId}:{toId}} 또는
 * {@code SUB:{subwayId}:{fromId}:{toId}}다. WALK와 비인접 구간에는 호출하지 않는다.
 * 문자열 생성 책임을 이 클래스 하나에 모아 Collector·Spark 계약이 바뀔 때 Matcher를
 * 수정하지 않도록 한다.</p>
 */
@Component
public class CanonicalSegmentIdFactory {

    private static final int MAX_COMPONENT_LENGTH = 50;

    public String create(TransportMode mode, String routeId, String fromNodeId, String toNodeId) {
        Objects.requireNonNull(mode, "mode must not be null");
        String prefix = switch (mode) {
            case BUS -> "BUS";
            case SUBWAY -> "SUB";
            case WALK -> throw new IllegalArgumentException("WALK에는 대중교통 segment_id를 발급하지 않습니다.");
        };
        return prefix + ':' + component(routeId, "routeId")
                + ':' + component(fromNodeId, "fromNodeId")
                + ':' + component(toNodeId, "toNodeId");
    }

    /**
     * 구분자·제어문자·공백을 허용하면 로그/Redis key 파싱이 달라질 수 있어 즉시 실패시킨다.
     * 각 공식 마스터 ID 열이 VARCHAR(50)이므로 같은 경계를 여기서도 강제한다.
     */
    private String component(String value, String label) {
        if (value == null || value.isBlank()
                || !value.equals(value.strip())
                || value.length() > MAX_COMPONENT_LENGTH
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    label + "는 1~50자의 영문·숫자·점·밑줄·하이픈만 사용할 수 있습니다.");
        }
        return value;
    }
}
