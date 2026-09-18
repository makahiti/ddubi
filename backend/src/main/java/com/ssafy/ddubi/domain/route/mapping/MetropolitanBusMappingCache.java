package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 수도권 버전 전용 매핑 캐시. 서울 전용 FK에 수도권 버전을 넣지 않는다.
 * 정책/검토일/입력 해시별 저장하고 수동 REJECTED를 자동 결과로 덮어쓰지 않는다.
 * EXACT만 재사용하며 부분 결과도 품질 분석을 위해 보존한다. 장애 시 Resolver가 재계산한다. */
@Repository
public class MetropolitanBusMappingCache {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public MetropolitanBusMappingCache(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public ProviderSegmentMappingLookup find(String signature,String version,String policy) {
        var rows=jdbc.query("SELECT review_status,result_json FROM metropolitan_bus_mapping_cache WHERE data_version=? AND policy_version=? AND provider_signature=?",
                (rs,n)->new String[]{rs.getString(1),rs.getString(2)},version,policy,signature);
        if(rows.isEmpty())return ProviderSegmentMappingLookup.miss();
        if(rows.get(0)[0].equals("REJECTED"))return ProviderSegmentMappingLookup.rejected();
        try {
            var result=json.readValue(rows.get(0)[1],TransitMappingResult.class);
            if(!version.equals(result.masterDataVersion()) || !policy.equals(result.mappingPolicyVersion()))
                throw new IllegalStateException("수도권 캐시 키와 본문 버전 불일치");
            return result.status()==TransitMappingStatus.EXACT?ProviderSegmentMappingLookup.exact(result):ProviderSegmentMappingLookup.miss();
        } catch(Exception e){throw new IllegalStateException("수도권 캐시 역직렬화 실패",e);}
    }
    public void save(String signature,String version,String policy,TransitMappingResult result) {
        try {
            String payload=json.writeValueAsString(result);
            jdbc.update("""
                INSERT INTO metropolitan_bus_mapping_cache(data_version,policy_version,provider_signature,result_json)
                VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE
                result_json=IF(review_status='REJECTED',result_json,VALUES(result_json)),updated_at=CURRENT_TIMESTAMP(6)
                """,version,policy,signature,payload);
        } catch(Exception e){throw new IllegalStateException("수도권 캐시 저장 실패",e);}
    }
}
