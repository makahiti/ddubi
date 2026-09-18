package com.ssafy.ddubi.domain.route.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MySQL의 활성 서울 교통 마스터를 매칭기가 사용하는 노선별 읽기 모델로 변환한다.
 *
 * <p>입력은 V1의 {@code seoul_*} 마스터 테이블이며 지하철 방향별 순번을
 * 반드시 보존한다. 활성 버전은 {@code pipeline_config}의
 * {@code seoul_transit_master_active_version} 값으로 선택한다. 포인터가 없으면 아직
 * 적재가 완료되지 않은 것으로 보고 빈 스냅샷을 반환하며, 다른 버전의 행을 섞지 않는다.</p>
 *
 * <p>출력은 {@link SubwayLineMaster} 목록이다. 이 Provider는
 * 애플리케이션 시작과 활성 버전 변경 시 호출되어 Matcher의 메모리 인덱스를 구성하므로
 * 카카오 요청마다 수만 행을 DB에서 읽지 않는다. Resolver의 주기 확인은 작은 활성 포인터와
 * 별칭만 읽고, 버전이 달라진 경우에만 이 전체 조회를 다시 실행한다.</p>
 */
@Repository
public class JdbcSeoulTransitMasterDataProvider implements SeoulTransitMasterDataProvider {

    private static final String ACTIVE_VERSION_KEY = "seoul_transit_master_active_version";

    private final JdbcTemplate jdbcTemplate;

    /** Backend 공용 DataSource의 JdbcTemplate을 사용하며 별도 연결이나 트랜잭션을 만들지 않는다. */
    public JdbcSeoulTransitMasterDataProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 활성 버전의 호선·방향별 역 순서를 각각 독립된 읽기 모델로 묶는다. */
    @Override
    public List<SubwayLineMaster> loadSubwayLines(String dataVersion) {
        if (dataVersion == null || dataVersion.isBlank()) {
            return List.of();
        }

        // key는 subwayId뿐 아니라 방향과 서비스 패턴을 포함한다. 이를 생략하면 본선·지선이 합쳐진다.
        Map<String, SubwayLineBuilder> lines = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        SELECT l.subway_id,
                               l.realtime_subway_id,
                               l.line_name,
                               l.direction,
                               l.service_pattern_id,
                               ls.station_sequence,
                               s.station_id,
                               s.realtime_station_id,
                               s.station_name
                          FROM seoul_subway_line l
                          JOIN seoul_subway_line_station ls
                            ON ls.data_version = l.data_version
                           AND ls.subway_id = l.subway_id
                           AND ls.direction = l.direction
                           AND ls.service_pattern_id = l.service_pattern_id
                          JOIN seoul_subway_station s
                            ON s.data_version = ls.data_version
                           AND s.station_id = ls.station_id
                         WHERE l.data_version = ?
                         ORDER BY l.subway_id, l.direction, l.service_pattern_id, ls.station_sequence
                        """,
                (RowCallbackHandler) resultSet -> appendSubwayStation(lines, resultSet),
                dataVersion);

        return lines.values().stream().map(SubwayLineBuilder::build).toList();
    }

    /** 검증된 별칭을 교통수단·대상별 alias→canonical ID 후보 집합으로 제공한다. */
    @Override
    public TransitAliasSnapshot loadAliases(String dataVersion) {
        if (dataVersion == null || dataVersion.isBlank()) {
            return TransitAliasSnapshot.empty();
        }
        Map<String, Set<String>> subwayLineAliasIds = new LinkedHashMap<>();
        Map<String, Set<String>> subwayStationAliasIds = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        SELECT mode, alias_type, normalized_name, canonical_id
                          FROM transit_alias
                         WHERE data_version=? AND provider='KAKAO'
                           AND mode='SUBWAY' AND verified=TRUE AND active=TRUE
                         ORDER BY id
                        """,
                (RowCallbackHandler) resultSet -> {
                    String mode = resultSet.getString("mode");
                    String type = resultSet.getString("alias_type");
                    Map<String, Set<String>> target;
                    if ("SUBWAY".equals(mode) && "LINE".equals(type)) {
                        target = subwayLineAliasIds;
                    } else if ("SUBWAY".equals(mode) && "STATION".equals(type)) {
                        target = subwayStationAliasIds;
                    } else {
                        throw new IllegalStateException(
                                "지원하지 않는 별칭 조합입니다: " + mode + '/' + type);
                    }
                    String alias = resultSet.getString("normalized_name");
                    String canonicalId = resultSet.getString("canonical_id");
                    // 동명 정류장·환승역은 노선별 ID가 다르므로 모두 보존하고 Matcher에서 scope한다.
                    target.computeIfAbsent(alias, ignored -> new LinkedHashSet<>()).add(canonicalId);
                },
                dataVersion);
        return new TransitAliasSnapshot(
                Map.of(), Map.of(), subwayLineAliasIds, subwayStationAliasIds);
    }

    /**
     * STAGING 행을 실수로 읽지 않도록 설정 포인터와 ACTIVE 상태를 동시에 확인한다.
     * 잘못된 포인터는 빈 결과가 되어 매핑 성공처럼 보이지 않는다.
     */
    @Override
    public Optional<String> activeVersion() {
        List<String> versions = jdbcTemplate.query("""
                        SELECT v.data_version
                          FROM pipeline_config c
                          JOIN seoul_transit_master_version v
                            ON v.data_version = c.config_value
                           AND v.status = 'ACTIVE'
                         WHERE c.config_key = ?
                         LIMIT 1
                        """,
                (resultSet, rowNumber) -> resultSet.getString("data_version"),
                ACTIVE_VERSION_KEY);
        return versions.stream().findFirst();
    }

    /** 호선·방향·패턴 복합키별 builder에 정적/실시간 역 ID를 분리해 추가한다. */
    private void appendSubwayStation(Map<String, SubwayLineBuilder> lines, ResultSet resultSet)
            throws SQLException {
        String subwayId = resultSet.getString("subway_id");
        String realtimeSubwayId = resultSet.getString("realtime_subway_id");
        String direction = resultSet.getString("direction");
        String servicePatternId = resultSet.getString("service_pattern_id");
        // 같은 방향에도 본선·지선·단축운행이 공존하므로 패턴까지 묶음 키에 포함한다.
        String lineKey = subwayId + '\u0000' + direction + '\u0000' + servicePatternId;
        SubwayLineBuilder line = lines.computeIfAbsent(lineKey, ignored -> new SubwayLineBuilder(
                subwayId,
                realtimeSubwayId,
                resultSetString(resultSet, "line_name"),
                direction,
                servicePatternId
        ));
        line.stations().add(new SubwayStationMaster(
                resultSet.getString("station_id"),
                resultSet.getString("realtime_station_id"),
                resultSet.getString("station_name"),
                resultSet.getInt("station_sequence")
        ));
    }

    /** computeIfAbsent 내부의 checked SQLException을 피하면서 필수 문자열을 읽는다. */
    private String resultSetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException("서울 교통 마스터 필드를 읽을 수 없습니다: " + column, exception);
        }
    }

    /** 방향·서비스 패턴별 여러 역 행을 하나의 불변 SubwayLineMaster로 만드는 내부 버퍼다. */
    private record SubwayLineBuilder(
            String subwayId,
            String realtimeSubwayId,
            String lineName,
            String direction,
            String servicePatternId,
            List<SubwayStationMaster> stations
    ) {
        private SubwayLineBuilder(
                String subwayId,
                String realtimeSubwayId,
                String lineName,
                String direction,
                String servicePatternId
        ) {
            this(subwayId, realtimeSubwayId, lineName, direction, servicePatternId, new ArrayList<>());
        }

        private SubwayLineMaster build() {
            return new SubwayLineMaster(
                    subwayId, realtimeSubwayId, lineName, direction, servicePatternId, stations);
        }
    }
}
