package com.ssafy.ddubi.domain.route.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** 승·하차 좌표 검증 근거. API 실패 사유와 좌표 검증 결과는 별도로 보존한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointEvidence(boolean verified, String source, String masterVersion, String mappingVersion,
        String policyVersion, Double distanceMeters, String coordinateType, String judgment, String reason) {
    public static final String POLICY_VERSION = "walk-endpoint-v2";

    public static EndpointEvidence unavailable(String reason) {
        return new EndpointEvidence(false, null, null, null, POLICY_VERSION, null, null, "UNVERIFIED", reason);
    }

    /** 이전 평가 JSON의 문자열 근거도 읽되 새 좌표 검증을 통과한 것으로 승격하지 않는다. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EndpointEvidence read(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return unavailable(value.asText().startsWith("EXACT:")
                ? "LEGACY_ENDPOINT_EVIDENCE" : value.asText());
        return new EndpointEvidence(value.path("verified").asBoolean(false), text(value, "source"),
                text(value, "masterVersion"), text(value, "mappingVersion"), text(value, "policyVersion"),
                value.path("distanceMeters").isNumber() ? value.path("distanceMeters").doubleValue() : null,
                text(value, "coordinateType"), text(value, "judgment"), text(value, "reason"));
    }

    private static String text(JsonNode value, String name) {
        return value.path(name).isTextual() ? value.path(name).asText() : null;
    }
}
