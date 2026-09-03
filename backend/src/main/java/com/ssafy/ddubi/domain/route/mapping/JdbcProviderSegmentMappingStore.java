package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code provider_segment_mapping}에 매핑 증거와 결과를 JSON으로 저장하는 MySQL 어댑터다.
 *
 * <p>조회 키는 provider + mode + signature + 활성 마스터·정책 버전이다. 새 마스터나
 * 별칭·판정 정책이 바뀌면 과거 캐시를 삭제하지 않아도 자동으로 재계산된다. 오판 고착을
 * 막기 위해 EXACT만 재사용하고 실패 결과는 품질 집계·재검증 근거로만 저장한다.
 * 사람이 {@code REJECTED}로 표시한 signature는 일반 miss와 구분해 Resolver의 재계산도 차단한다.</p>
 */
@Repository
public class JdbcProviderSegmentMappingStore implements ProviderSegmentMappingStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcProviderSegmentMappingStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 복합키 한 행을 조회해 MISS, 재사용 가능한 EXACT, 수동 REJECTED 중 하나로 반환한다.
     * active=false와 실패 상태는 재계산 가능한 MISS지만 REJECTED는 같은 오판을 다시 만들지 않는 차단값이다.
     */
    @Override
    public ProviderSegmentMappingLookup find(
            String provider,
            TransportMode mode,
            String signature,
            String mappingVersion,
            String policyVersion) {
        List<MappingRow> rows = jdbcTemplate.query("""
                        SELECT mapping_status, review_status, active, result_json
                          FROM provider_segment_mapping
                         WHERE provider=? AND mode=? AND provider_signature=? AND mapping_version=?
                           AND policy_version=?
                         LIMIT 1
                        """, (resultSet, rowNumber) -> new MappingRow(
                        resultSet.getString("mapping_status"),
                        resultSet.getString("review_status"),
                        resultSet.getBoolean("active"),
                        resultSet.getString("result_json")),
                provider, mode.name(), signature, mappingVersion, policyVersion);
        if (rows.isEmpty()) {
            return ProviderSegmentMappingLookup.miss();
        }
        MappingRow row = rows.get(0);
        // 수동 거절은 active 플래그보다 우선한다. 오판 차단을 단순 캐시 비활성화로 축소하지 않는다.
        if ("REJECTED".equals(row.reviewStatus())) {
            return ProviderSegmentMappingLookup.rejected();
        }
        if (!row.active() || !"EXACT".equals(row.mappingStatus())) {
            return ProviderSegmentMappingLookup.miss();
        }
        return ProviderSegmentMappingLookup.exact(
                readResult(row.resultJson(), mappingVersion, policyVersion));
    }

    /**
     * 현재 판정과 최소 재검증 증거를 upsert한다. 수동 review_status와 active는 UPDATE 목록에서
     * 제외하므로 자동 재계산이 운영자의 승인·거절 결정을 덮어쓰지 않는다.
     */
    @Override
    public void save(
            String provider,
            String signature,
            String mappingVersion,
            String policyVersion,
            SegmentResolveRequest request,
            TransitMappingResult result
    ) {
        if (result.status() == TransitMappingStatus.NOT_APPLICABLE) {
            return;
        }
        jdbcTemplate.update("""
                        INSERT INTO provider_segment_mapping
                            (provider, mode, provider_signature, mapping_version, policy_version, mapping_status,
                             canonical_route_id, from_node_id, to_node_id, evidence_json,
                             result_json, resolved_at, active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                CASE WHEN ?='EXACT' THEN CURRENT_TIMESTAMP(6) ELSE NULL END, TRUE)
                        ON DUPLICATE KEY UPDATE
                            mapping_status=VALUES(mapping_status),
                            canonical_route_id=VALUES(canonical_route_id),
                            from_node_id=VALUES(from_node_id),
                            to_node_id=VALUES(to_node_id),
                            evidence_json=VALUES(evidence_json),
                            result_json=VALUES(result_json),
                            resolved_at=VALUES(resolved_at)
                        """,
                provider, request.mode().name(), signature, mappingVersion, policyVersion,
                result.status().name(),
                result.officialRouteId(), result.fromNodeId(), result.toNodeId(),
                writeJson(evidence(request)), writeJson(result), result.status().name());
    }

    private TransitMappingResult readResult(
            String json,
            String expectedMappingVersion,
            String expectedPolicyVersion
    ) {
        try {
            TransitMappingResult result = objectMapper.readValue(json, TransitMappingResult.class);
            if (result.status() != TransitMappingStatus.EXACT
                    || result.segments().isEmpty()
                    || !expectedMappingVersion.equals(result.masterDataVersion())
                    || !expectedPolicyVersion.equals(result.mappingPolicyVersion())
                    || result.segments().stream().anyMatch(segment ->
                    segment.segmentId() == null || segment.segmentId().isBlank())) {
                throw new IllegalStateException("저장된 EXACT 캐시의 의미 무결성이 깨졌습니다.");
            }
            return result;
        } catch (JsonProcessingException exception) {
            // 깨진 캐시를 성공처럼 사용하지 않는다. 운영자는 해당 행과 로그로 원인을 추적할 수 있다.
            throw new IllegalStateException("저장된 교통 매핑 결과 JSON을 읽을 수 없습니다.", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("교통 매핑 증거 JSON을 만들 수 없습니다.", exception);
        }
    }

    /**
     * 재검증에 필요한 이름·순서와 BUS 첫/끝 좌표만 보존한다. 지도 표시용 전체 polyline을
     * 캐시 행마다 중복 저장하지 않아 MySQL 용량과 개인정보성 이동 궤적 노출을 줄인다.
     */
    private MappingEvidence evidence(SegmentResolveRequest request) {
        List<com.ssafy.ddubi.domain.route.model.Coordinate> endpoints;
        if (request.geometry().size() < 2) {
            endpoints = List.of();
        } else {
            endpoints = List.of(
                    request.geometry().get(0),
                    request.geometry().get(request.geometry().size() - 1));
        }
        return new MappingEvidence(
                request.mode(),
                request.providerRouteKey(),
                request.fromName(),
                request.toName(),
                request.stopNames(),
                request.vehicleNames(),
                endpoints);
    }

    private record MappingEvidence(
            TransportMode mode,
            String providerRouteKey,
            String fromName,
            String toName,
            List<String> stopNames,
            List<String> vehicleNames,
            List<com.ssafy.ddubi.domain.route.model.Coordinate> endpointGeometry
    ) { }

    /** JSON 역직렬화 전에 수동 검토 상태를 먼저 판정하기 위한 최소 조회 행이다. */
    private record MappingRow(
            String mappingStatus,
            String reviewStatus,
            boolean active,
            String resultJson
    ) { }
}
