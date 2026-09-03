package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 서울 공식 버스 XLSX와 지하철 운행시각표 CSV를 버전 마스터 테이블에 적재한다.
 *
 * <p>버스 입력 열은 ROUTE_ID, 노선명, 순번, NODE_ID, ARS_ID, 정류소명, X좌표, Y좌표다.
 * 지하철 입력은 CP949 CSV이며 호선, 역사코드, 역사명, 방향, 급행여부, 열차코드,
 * 도착·출발시각을 사용한다. 급행은 물리적으로 인접하지 않은 역을 건너뛰므로 마스터
 * 인접 구간 생성에서 제외한다. 일반 열차의 고유 역 순서를 서비스 패턴으로 보존하여
 * 본선·지선·단축 운행을 하나의 잘못된 선형 순서로 합치지 않는다.</p>
 *
 * <p>전체 입력 검증과 INSERT는 한 트랜잭션에서 처리한다. 성공 전까지 새 버전은 STAGING이며,
 * 모든 행이 들어간 뒤에만 ACTIVE 포인터를 교체한다. 같은 dataVersion 재실행은 중복 적재를
 * 막기 위해 실패한다.</p>
 */
@Service
public class SeoulTransitMasterImportService {

    // 서울교통공사 원본 CSV의 실제 문자셋이다. UTF-8로 읽으면 역명이 깨져 매핑률이 0으로 떨어진다.
    private static final Charset KOREAN_CSV = Charset.forName("MS949");
    // 한 번에 500행씩 전송해 5만 건 안팎의 마스터에서 왕복 횟수와 JDBC 패킷 크기의 균형을 맞춘다.
    // MySQL max_allowed_packet이나 네트워크 조건이 바뀌면 적재 성능·메모리 사용량을 함께 재측정한다.
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Spring이 관리하는 JdbcTemplate을 주입받아 서비스 DB 쓰기를 한 연결 정책으로 통일한다. */
    public SeoulTransitMasterImportService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 두 공식 파일을 검증·적재하고 활성 버전을 원자적으로 교체한다. */
    @Transactional(rollbackFor = Exception.class)
    public ImportSummary importAndActivate(
            String dataVersion,
            Path busWorkbook,
            Path subwayTimetable
    ) throws Exception {
        return importAndActivate(dataVersion, busWorkbook, subwayTimetable, null, null);
    }

    /** 정적 코드↔실시간 statnId JSON이 있으면 같은 트랜잭션에서 역 마스터를 보강한다. */
    @Transactional(rollbackFor = Exception.class)
    public ImportSummary importAndActivate(
            String dataVersion,
            Path busWorkbook,
            Path subwayTimetable,
            Path subwayRealtimeStationMapping
    ) throws Exception {
        return importAndActivate(
                dataVersion, busWorkbook, subwayTimetable, subwayRealtimeStationMapping, null);
    }

    /** 실시간 역 ID와 검증된 별칭 JSON까지 한 버전으로 묶어 적재한다. */
    @Transactional(rollbackFor = Exception.class)
    public ImportSummary importAndActivate(
            String dataVersion,
            Path busWorkbook,
            Path subwayTimetable,
            Path subwayRealtimeStationMapping,
            Path transitAliases
    ) throws Exception {
        // 1단계: 트랜잭션을 열기 전에 입력 경로와 버전 형식을 확인한다.
        // 선택 파일은 null일 수 있지만, 전달된 파일이 읽히지 않으면 대응표 일부만 빠진 버전을 만들지 않는다.
        requireReadable(busWorkbook, "버스 노선별 정류소 XLSX");
        requireReadable(subwayTimetable, "지하철 운행시각표 CSV");
        if (subwayRealtimeStationMapping != null) {
            requireReadable(subwayRealtimeStationMapping, "지하철 정적·실시간 역 ID JSON");
        }
        if (transitAliases != null) {
            requireReadable(transitAliases, "검증된 교통 별칭 JSON");
        }
        if (dataVersion == null || dataVersion.isBlank() || dataVersion.length() > 50) {
            throw new IllegalArgumentException("dataVersion은 1~50자의 값이어야 합니다.");
        }

        // 2단계: dataVersion은 불변 스냅샷 식별자다. 같은 이름에 다른 파일을 덮어쓰면 Replay가 깨지므로 거절한다.
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seoul_transit_master_version WHERE data_version = ?",
                Integer.class,
                dataVersion);
        if (existing != null && existing > 0) {
            throw new IllegalStateException("이미 존재하는 서울 마스터 버전입니다: " + dataVersion);
        }

        // 3단계: 먼저 STAGING 껍데기를 만든다. 이후 파싱·검증·INSERT 중 하나라도 실패하면
        // @Transactional이 이 행까지 롤백하므로 ACTIVE 포인터는 기존 정상 버전을 계속 가리킨다.
        jdbcTemplate.update("""
                        INSERT INTO seoul_transit_master_version
                            (data_version, status, source_name)
                        VALUES (?, 'STAGING', ?)
                        """,
                dataVersion,
                busWorkbook.getFileName() + " + " + subwayTimetable.getFileName());

        // 4단계: 외부 파일을 DB 행과 동일한 의미의 메모리 스냅샷으로 변환한 뒤 전체 교차 검증한다.
        // 행을 읽자마자 INSERT하지 않아 뒤쪽 오류 때문에 반쪽 마스터가 남는 상황을 피한다.
        BusSnapshot bus = readBusWorkbook(busWorkbook);
        SubwaySnapshot subway = readSubwayTimetable(subwayTimetable);
        validate(bus, subway);
        // 5단계: 정적 topology를 먼저 저장해야 선택 crosswalk와 alias의 canonical ID를 역조회할 수 있다.
        insertBus(dataVersion, bus);
        insertSubway(dataVersion, subway);
        int realtimeStationMappingCount = subwayRealtimeStationMapping == null
                ? 0 : applyRealtimeStationMapping(dataVersion, subwayRealtimeStationMapping);
        int transitAliasCount = transitAliases == null ? 0 : insertAliases(dataVersion, transitAliases);

        // 6단계: 모든 본문과 선택 대응표가 성공한 마지막 순간에만 ACTIVE 포인터를 교체한다.
        // 포인터 변경 전 예외는 트랜잭션 전체를 롤백하므로 서비스는 직전 ACTIVE를 계속 사용한다.
        jdbcTemplate.update("UPDATE seoul_transit_master_version SET status='RETIRED' WHERE status='ACTIVE'");
        jdbcTemplate.update("""
                        UPDATE seoul_transit_master_version
                           SET status='ACTIVE', activated_at=CURRENT_TIMESTAMP(6)
                         WHERE data_version=?
                        """, dataVersion);
        jdbcTemplate.update("""
                        INSERT INTO pipeline_config(config_key, config_value)
                        VALUES ('seoul_transit_master_active_version', ?)
                        ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)
                        """, dataVersion);

        return new ImportSummary(
                dataVersion,
                bus.routes().size(),
                bus.stops().size(),
                bus.routeStops().size(),
                subway.lines().size(),
                subway.stations().size(),
                subway.lineStations().size(),
                realtimeStationMappingCount,
                transitAliasCount
        );
    }

    /**
     * 별칭 JSON을 기본 정규화한 뒤 공식 ID와 공식 이름의 대응을 확인하여 저장한다.
     * verified=false 행도 감사 목적으로 저장하지만 Matcher는 verified=true만 읽는다.
     */
    private int insertAliases(String version, Path path) throws Exception {
        List<TransitAliasRow> rows = objectMapper.readValue(
                Files.readAllBytes(path), new TypeReference<List<TransitAliasRow>>() { });
        TransitNameNormalizer normalizer = new TransitNameNormalizer();
        for (TransitAliasRow row : rows) {
            String mode = requiredEnum(row.mode(), Set.of("BUS", "SUBWAY"), "mode");
            String provider = row.provider() == null || row.provider().isBlank()
                    ? "KAKAO"
                    : row.provider().strip().toUpperCase(java.util.Locale.ROOT);
            String aliasType = requiredEnum(
                    row.aliasType(), Set.of("ROUTE", "STOP", "LINE", "STATION"), "aliasType");
            if ((mode.equals("BUS") && !(aliasType.equals("ROUTE") || aliasType.equals("STOP")))
                    || (mode.equals("SUBWAY") && !(aliasType.equals("LINE") || aliasType.equals("STATION")))) {
                throw new IllegalArgumentException("교통수단과 별칭 종류가 맞지 않습니다: " + mode + '/' + aliasType);
            }
            requireAliasValue(row.aliasName(), "aliasName");
            requireAliasValue(row.canonicalId(), "canonicalId");
            // canonicalName은 사람이 입력한 문자열을 신뢰하지 않고 canonicalId로 마스터에서 역조회한다.
            String officialCanonicalName = findCanonicalName(
                    version, mode, aliasType, row.canonicalId());
            if (row.canonicalName() != null && !row.canonicalName().isBlank()
                    && !normalizeAliasTarget(normalizer, aliasType, row.canonicalName())
                    .equals(normalizeAliasTarget(normalizer, aliasType, officialCanonicalName))) {
                throw new IllegalArgumentException(
                        "별칭 canonicalId와 canonicalName이 같은 공식 대상을 가리키지 않습니다: "
                                + row.canonicalId() + '/' + row.canonicalName());
            }
            String normalized = switch (aliasType) {
                case "ROUTE" -> normalizer.normalizeRouteName(row.aliasName());
                case "STOP" -> normalizer.normalizeStopName(row.aliasName());
                case "LINE" -> normalizer.normalizeSubwayLineName(row.aliasName());
                case "STATION" -> normalizer.normalizeSubwayStationName(row.aliasName());
                default -> throw new IllegalStateException("검증되지 않은 aliasType");
            };
            jdbcTemplate.update("""
                            INSERT INTO transit_alias
                                (data_version,provider,mode,alias_type,alias_name,normalized_name,
                                 canonical_id,canonical_name,verified,active)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                            """,
                    version, provider,
                    mode, aliasType, row.aliasName(), normalized, row.canonicalId(), officialCanonicalName,
                    Boolean.TRUE.equals(row.verified()));
        }
        return rows.size();
    }

    /**
     * 별칭 대상은 실시간 crosswalk ID가 아니라 버전 간 안정적인 정적 canonical ID만 허용한다.
     * 호선은 여러 방향·서비스 패턴 행이 있으므로 DISTINCT 이름이 정확히 하나인지도 확인한다.
     */
    /**
     * 별칭 파일의 표시 이름을 신뢰하지 않고 canonical ID가 실제 새 마스터에 존재하는지 역조회한다.
     * 조회 결과가 0건이면 orphan 별칭, 여러 이름이면 동일 ID 충돌이므로 둘 다 적재를 중단한다.
     */
    private String findCanonicalName(
            String version,
            String mode,
            String aliasType,
            String canonicalId
    ) {
        String sql = switch (aliasType) {
            case "ROUTE" -> "SELECT DISTINCT route_name FROM seoul_bus_route WHERE data_version=? AND route_id=?";
            case "STOP" -> "SELECT DISTINCT stop_name FROM seoul_bus_stop WHERE data_version=? AND stop_id=?";
            case "LINE" -> "SELECT DISTINCT line_name FROM seoul_subway_line WHERE data_version=? AND subway_id=?";
            case "STATION" -> "SELECT DISTINCT station_name FROM seoul_subway_station WHERE data_version=? AND station_id=?";
            default -> throw new IllegalArgumentException("지원하지 않는 aliasType입니다: " + aliasType);
        };
        List<String> names = jdbcTemplate.query(sql,
                (resultSet, rowNumber) -> resultSet.getString(1), version, canonicalId);
        if (names.size() != 1) {
            throw new IllegalArgumentException(
                    "별칭의 정적 canonical ID가 현재 마스터에서 하나로 확정되지 않습니다: "
                            + mode + '/' + canonicalId);
        }
        return names.get(0);
    }

    private String normalizeAliasTarget(
            TransitNameNormalizer normalizer,
            String aliasType,
            String value
    ) {
        return switch (aliasType) {
            case "ROUTE" -> normalizer.normalizeRouteName(value);
            case "STOP" -> normalizer.normalizeStopName(value);
            case "LINE" -> normalizer.normalizeSubwayLineName(value);
            case "STATION" -> normalizer.normalizeSubwayStationName(value);
            default -> throw new IllegalArgumentException("지원하지 않는 aliasType입니다: " + aliasType);
        };
    }

    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + " 값이 올바르지 않습니다: " + value);
        }
        return normalized;
    }

    private void requireAliasValue(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("별칭 " + label + " 값이 비었습니다.");
        }
    }

    /**
     * 검증된 대응 JSON의 정적 STATION_CD를 기준으로 FR_CODE와 statnId를 갱신한다.
     * 파일에 없는 역은 NULL로 남아 데이터 부족을 숨기지 않으며, 파일에 있는 코드가 현재
     * 활성화 후보 마스터에 없으면 버전 혼합으로 보고 전체 적재를 롤백한다.
     */
    /**
     * 정적 호선·역과 서울 실시간 API ID의 crosswalk를 파일 전체 검증 후 적용한다.
     * static→realtime과 realtime→static 양방향 유일성을 먼저 검사해 한 실시간 관측이
     * 서로 다른 canonical 호선으로 분기되는 오염을 UPDATE 전에 차단한다.
     */
    private int applyRealtimeStationMapping(String version, Path path) throws Exception {
        List<SubwayRealtimeStationRow> rows = objectMapper.readValue(
                Files.readAllBytes(path), new TypeReference<List<SubwayRealtimeStationRow>>() { });
        Set<String> staticCodes = new LinkedHashSet<>();
        Set<String> realtimeStationIds = new LinkedHashSet<>();
        Map<String, String> realtimeLineIds = new LinkedHashMap<>();
        Map<String, String> staticLineIdsByRealtimeId = new LinkedHashMap<>();
        List<ValidatedRealtimeStationRow> validatedRows = new ArrayList<>(rows.size());

        // DB를 갱신하기 전에 파일 전체의 1:1 대응을 먼저 검증한다. 한 행씩 UPDATE하면서 검사하면
        // 뒤쪽 충돌을 발견하기 전까지 잘못된 crosswalk가 보일 수 있고 트랜잭션 의존성이 커진다.
        for (SubwayRealtimeStationRow row : rows) {
            String lineNumber = requiredMappingValue(row.lineNumber(), "lineNumber");
            String realtimeSubwayId = requiredMappingValue(row.subwayId(), "subwayId");
            String staticStationCode = requiredMappingValue(
                    row.staticStationCode(), "staticStationCode");
            String realtimeStationId = requiredMappingValue(
                    row.realtimeStationId(), "realtimeStationId");
            String staticLineId = subwayId(lineNumber);

            String previousLineId = realtimeLineIds.putIfAbsent(staticLineId, realtimeSubwayId);
            if (previousLineId != null && !previousLineId.equals(realtimeSubwayId)) {
                throw new IllegalArgumentException(
                        "한 정적 호선에 서로 다른 실시간 subwayId가 있습니다: " + lineNumber);
            }
            String previousStaticLineId = staticLineIdsByRealtimeId.putIfAbsent(
                    realtimeSubwayId, staticLineId);
            if (previousStaticLineId != null && !previousStaticLineId.equals(staticLineId)) {
                throw new IllegalArgumentException(
                        "한 실시간 subwayId가 서로 다른 정적 호선에 연결됩니다: "
                                + realtimeSubwayId + " -> " + previousStaticLineId + "," + staticLineId);
            }
            if (!staticCodes.add(staticStationCode)) {
                throw new IllegalArgumentException(
                        "지하철 실시간 ID 대응표의 정적 역 코드가 중복됩니다: " + staticStationCode);
            }
            if (!realtimeStationIds.add(realtimeStationId)) {
                throw new IllegalArgumentException(
                        "지하철 실시간 statnId가 서로 다른 정적 역에 중복됩니다: "
                                + realtimeStationId);
            }
            validatedRows.add(new ValidatedRealtimeStationRow(
                    staticLineId,
                    realtimeSubwayId,
                    staticStationCode,
                    blankToNull(row.externalCode()),
                    realtimeStationId));
        }

        for (ValidatedRealtimeStationRow row : validatedRows) {
            Integer membership = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                              FROM seoul_subway_line_station
                             WHERE data_version=? AND subway_id=? AND station_id=?
                            """, Integer.class,
                    version, row.staticLineId(), row.staticStationCode());
            if (membership == null || membership < 1) {
                throw new IllegalArgumentException(
                        "정적 역 코드가 지정한 호선에 속하지 않습니다: "
                                + row.staticLineId() + '/' + row.staticStationCode());
            }
            int updated = jdbcTemplate.update("""
                            UPDATE seoul_subway_station
                               SET external_code=?, realtime_station_id=?
                             WHERE data_version=? AND station_id=?
                            """,
                    row.externalCode(), row.realtimeStationId(), version, row.staticStationCode());
            if (updated != 1) {
                throw new IllegalArgumentException(
                        "현재 지하철 마스터에서 정적 역 코드를 찾을 수 없습니다: " + row.staticStationCode());
            }
        }
        for (Map.Entry<String, String> line : realtimeLineIds.entrySet()) {
            int updated = jdbcTemplate.update("""
                            UPDATE seoul_subway_line
                               SET realtime_subway_id=?
                             WHERE data_version=? AND subway_id=?
                            """, line.getValue(), version, line.getKey());
            if (updated < 1) {
                throw new IllegalArgumentException(
                        "현재 지하철 마스터에서 호선 코드를 찾을 수 없습니다: " + line.getKey());
            }
        }
        return rows.size();
    }

    /** 버스 XLSX를 공식 ID별로 중복 제거하되 노선별 정류장 순번 행은 하나도 합치지 않는다. */
    /**
     * 서울 버스 XLSX를 노선, 정류장, 노선별 순서의 세 집합으로 분리한다.
     * 같은 ID가 서로 다른 이름·좌표로 반복되면 조용히 마지막 값을 택하지 않고 즉시 실패시킨다.
     */
    private BusSnapshot readBusWorkbook(Path path) throws Exception {
        Map<String, BusRouteRow> routes = new LinkedHashMap<>();
        Map<String, BusStopRow> stops = new LinkedHashMap<>();
        List<BusRouteStopRow> routeStops = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> columns = header(sheet.getRow(0), formatter);
            requireColumns(columns, "ROUTE_ID", "노선명", "순번", "NODE_ID", "ARS_ID",
                    "정류소명", "X좌표", "Y좌표");

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String routeId = cell(row, columns.get("ROUTE_ID"), formatter);
                String routeName = cell(row, columns.get("노선명"), formatter);
                String stopId = cell(row, columns.get("NODE_ID"), formatter);
                String stopName = cell(row, columns.get("정류소명"), formatter);
                if (routeId.isBlank() || routeName.isBlank() || stopId.isBlank() || stopName.isBlank()) {
                    throw new IllegalArgumentException("버스 마스터 필수값 누락: XLSX " + (rowIndex + 1) + "행");
                }
                int sequence = integerCell(row, columns.get("순번"), formatter, rowIndex);
                putConsistent(routes, routeId, new BusRouteRow(routeId, routeName), "버스 노선");
                putConsistent(stops, stopId, new BusStopRow(
                        stopId,
                        blankToNull(cell(row, columns.get("ARS_ID"), formatter)),
                        stopName,
                        decimalCell(row, columns.get("X좌표"), formatter),
                        decimalCell(row, columns.get("Y좌표"), formatter)
                ), "버스 정류장");
                routeStops.add(new BusRouteStopRow(routeId, stopId, sequence));
            }
        }
        return new BusSnapshot(List.copyOf(routes.values()), List.copyOf(stops.values()), routeStops);
    }

    /**
     * 일반 열차별 시각 순서를 역 순서로 복원하고 동일한 전체 역 경로를 하나의 패턴으로 합친다.
     * 급행·결측·한 역짜리 운행은 인접 구간 기준으로 사용할 수 없어 제외하거나 명시적으로 실패시킨다.
     */
    /**
     * MS949 운행시각표를 일반열차별 정차 순서로 묶어 방향·서비스 패턴 topology를 만든다.
     * 급행 정차역만 연결하면 물리적으로 인접하지 않은 역이 edge가 되므로 급행은 제외한다.
     * 열차코드·요일·방향이 비면 서로 다른 운행이 합쳐지므로 TrainKey 생성 전에 필수값을 검증한다.
     */
    private SubwaySnapshot readSubwayTimetable(Path path) throws Exception {
        Map<TrainKey, List<TimetableStop>> trains = new HashMap<>();
        Map<String, String> stationNames = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(path, KOREAN_CSV)) {
            List<String> headers = parseCsvLine(requiredLine(reader, "지하철 CSV 헤더"));
            Map<String, Integer> columns = header(headers);
            requireColumns(columns, "호선", "역사코드", "역사명", "주중주말", "방향", "급행여부",
                    "열차코드", "열차도착시간", "열차출발시간");

            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                List<String> values = parseCsvLine(line);
                if (values.size() < headers.size()) {
                    throw new IllegalArgumentException("지하철 CSV 열 개수 오류: " + rowNumber + "행");
                }
                // 급행 행도 원본 품질 통계와 추적 대상이므로 핵심 식별자는 먼저 검증한다. 다만
                // 급행 정차역을 서로 인접한 물리 구간으로 오인하지 않도록 topology 생성에서는 제외한다.
                String expressType = requiredTopologyValue(
                        value(values, columns, "급행여부"), "급행여부", rowNumber);
                // 이 값 중 하나라도 비면 서로 다른 열차가 같은 TrainKey로 합쳐지거나 빈 역 ID가
                // canonical segment에 들어간다. NOT NULL은 빈 문자열을 막지 못하므로 파싱 단계에서 실패한다.
                String lineNumber = requiredTopologyValue(
                        value(values, columns, "호선"), "호선", rowNumber);
                String direction = requiredTopologyValue(
                        value(values, columns, "방향"), "방향", rowNumber);
                String stationId = requiredTopologyValue(
                        value(values, columns, "역사코드"), "역사코드", rowNumber);
                String stationName = requiredTopologyValue(
                        value(values, columns, "역사명"), "역사명", rowNumber);
                String trainCode = requiredTopologyValue(
                        value(values, columns, "열차코드"), "열차코드", rowNumber);
                String dayType = requiredTopologyValue(
                        value(values, columns, "주중주말"), "주중주말", rowNumber);
                if (!"0".equals(expressType)) {
                    continue;
                }
                String time = value(values, columns, "열차도착시간");
                if (time.isBlank()) {
                    time = value(values, columns, "열차출발시간");
                }
                time = requiredTopologyValue(time, "열차도착시간/열차출발시간", rowNumber);
                TrainKey key = new TrainKey(
                        lineNumber,
                        direction,
                        trainCode,
                        dayType
                );
                trains.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new TimetableStop(timeOrder(time), stationId, stationName));
                String previousStationName = stationNames.putIfAbsent(stationId, stationName);
                if (previousStationName != null && !previousStationName.equals(stationName)) {
                    throw new IllegalArgumentException(
                            "같은 정적 역 코드에 서로 다른 역명이 있습니다: " + stationId);
                }
            }
        }

        Map<String, SubwayLineRow> lines = new LinkedHashMap<>();
        Map<String, SubwayStationRow> stations = new LinkedHashMap<>();
        List<SubwayLineStationRow> lineStations = new ArrayList<>();
        Set<String> uniquePatterns = new LinkedHashSet<>();

        for (Map.Entry<TrainKey, List<TimetableStop>> entry : trains.entrySet()) {
            TrainKey train = entry.getKey();
            List<TimetableStop> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(TimetableStop::timeOrder))
                    .toList();
            List<TimetableStop> deduplicated = removeConsecutiveDuplicates(ordered);
            if (deduplicated.size() < 2) {
                continue;
            }
            String subwayId = subwayId(train.lineNumber());
            String stationPath = deduplicated.stream().map(TimetableStop::stationId)
                    .reduce((left, right) -> left + '>' + right).orElseThrow();
            String patternId = sha256(train.lineNumber() + '|' + train.direction() + '|' + stationPath);
            String patternKey = subwayId + '|' + train.direction() + '|' + patternId;
            if (!uniquePatterns.add(patternKey)) {
                continue;
            }
            lines.put(patternKey, new SubwayLineRow(
                    subwayId, train.lineNumber() + "호선", train.direction(), patternId));
            for (int index = 0; index < deduplicated.size(); index++) {
                TimetableStop stop = deduplicated.get(index);
                stations.putIfAbsent(stop.stationId(),
                        new SubwayStationRow(stop.stationId(), stationNames.get(stop.stationId())));
                lineStations.add(new SubwayLineStationRow(
                        subwayId, train.direction(), patternId, stop.stationId(), index + 1));
            }
        }
        return new SubwaySnapshot(List.copyOf(lines.values()), List.copyOf(stations.values()), lineStations);
    }

    /**
     * 비어 있지 않다는 확인에 그치지 않고 참조·순번·최소 topology 단위를 검증한다.
     * 잘린 파일의 절대 행 수 검증은 source manifest가 별도로 필요하지만, 최소한 고아 참조나
     * 한 정류장/역짜리 노선을 ACTIVE로 전환하지는 않는다.
     */
    /**
     * ACTIVE 전환 전에 참조 무결성, 순번, 좌표 범위와 최소 topology 크기를 메모리에서 검증한다.
     * 이 검사는 파일 내부 일관성 게이트이며, 공식 배포본의 완전성을 증명하는 checksum·기대 건수
     * manifest는 별도 후속 계약이다.
     */
    private void validate(BusSnapshot bus, SubwaySnapshot subway) {
        if (bus.routes().isEmpty() || bus.stops().isEmpty() || bus.routeStops().isEmpty()) {
            throw new IllegalArgumentException("버스 마스터가 비어 있습니다.");
        }
        if (subway.lines().isEmpty() || subway.stations().isEmpty() || subway.lineStations().isEmpty()) {
            throw new IllegalArgumentException("지하철 마스터가 비어 있습니다.");
        }

        Set<String> busRouteIds = bus.routes().stream().map(BusRouteRow::routeId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> busStopIds = bus.stops().stream().map(BusStopRow::stopId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Set<Integer>> busSequences = new HashMap<>();
        for (BusRouteStopRow row : bus.routeStops()) {
            if (!busRouteIds.contains(row.routeId()) || !busStopIds.contains(row.stopId())) {
                throw new IllegalArgumentException("버스 노선-정류장 관계에 고아 ID가 있습니다.");
            }
            if (row.sequence() < 1
                    || !busSequences.computeIfAbsent(row.routeId(), ignored -> new LinkedHashSet<>())
                    .add(row.sequence())) {
                throw new IllegalArgumentException(
                        "버스 노선 안 순번이 잘못됐거나 중복됩니다: " + row.routeId() + '/' + row.sequence());
            }
        }
        if (busRouteIds.stream().anyMatch(routeId ->
                busSequences.getOrDefault(routeId, Set.of()).size() < 2)) {
            throw new IllegalArgumentException("정류장이 2개 미만인 버스 노선이 있습니다.");
        }
        for (BusStopRow stop : bus.stops()) {
            if ((stop.longitude() == null) != (stop.latitude() == null)) {
                throw new IllegalArgumentException("버스 정류장 좌표 한쪽만 비었습니다: " + stop.stopId());
            }
            if (stop.longitude() != null
                    && (stop.longitude().compareTo(BigDecimal.valueOf(124)) < 0
                    || stop.longitude().compareTo(BigDecimal.valueOf(132)) > 0
                    || stop.latitude().compareTo(BigDecimal.valueOf(33)) < 0
                    || stop.latitude().compareTo(BigDecimal.valueOf(39)) > 0)) {
                throw new IllegalArgumentException("버스 정류장 좌표가 수도권 범위를 벗어납니다: " + stop.stopId());
            }
        }

        Set<String> subwayStationIds = subway.stations().stream()
                .map(SubwayStationRow::stationId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> lineKeys = subway.lines().stream().map(row ->
                        row.subwayId() + '\u0000' + row.direction() + '\u0000' + row.patternId())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Set<Integer>> patternSequences = new HashMap<>();
        for (SubwayLineStationRow row : subway.lineStations()) {
            String lineKey = row.subwayId() + '\u0000' + row.direction() + '\u0000' + row.patternId();
            if (!lineKeys.contains(lineKey) || !subwayStationIds.contains(row.stationId())) {
                throw new IllegalArgumentException("지하철 패턴-역 관계에 고아 ID가 있습니다.");
            }
            if (row.sequence() < 1
                    || !patternSequences.computeIfAbsent(lineKey, ignored -> new LinkedHashSet<>())
                    .add(row.sequence())) {
                throw new IllegalArgumentException("지하철 패턴 안 역 순번이 중복됩니다: " + lineKey);
            }
        }
        if (lineKeys.stream().anyMatch(lineKey ->
                patternSequences.getOrDefault(lineKey, Set.of()).size() < 2)) {
            throw new IllegalArgumentException("역이 2개 미만인 지하철 운행 패턴이 있습니다.");
        }
    }

    /** 같은 공식 ID의 속성이 행마다 달라지는 원본을 첫 행 우선으로 조용히 합치지 않는다. */
    private <T> void putConsistent(Map<String, T> target, String id, T value, String label) {
        T previous = target.putIfAbsent(id, value);
        if (previous != null && !previous.equals(value)) {
            throw new IllegalArgumentException(label + " ID의 속성이 서로 다릅니다: " + id);
        }
    }

    /** FK 순서에 맞춰 노선→정류장→노선정류장 관계 순서로 적재한다. */
    private void insertBus(String version, BusSnapshot snapshot) {
        batch("INSERT INTO seoul_bus_route(data_version,route_id,route_name) VALUES (?,?,?)",
                snapshot.routes().stream().map(row -> new Object[]{version, row.routeId(), row.routeName()}).toList());
        batch("INSERT INTO seoul_bus_stop(data_version,stop_id,ars_id,stop_name,longitude,latitude) VALUES (?,?,?,?,?,?)",
                snapshot.stops().stream().map(row -> new Object[]{version, row.stopId(), row.arsId(), row.stopName(), row.longitude(), row.latitude()}).toList());
        batch("INSERT INTO seoul_bus_route_stop(data_version,route_id,stop_id,stop_sequence) VALUES (?,?,?,?)",
                snapshot.routeStops().stream().map(row -> new Object[]{version, row.routeId(), row.stopId(), row.sequence()}).toList());
    }

    /** FK 순서에 맞춰 패턴→역→패턴역 관계 순서로 적재한다. */
    private void insertSubway(String version, SubwaySnapshot snapshot) {
        batch("INSERT INTO seoul_subway_line(data_version,subway_id,direction,service_pattern_id,line_name) VALUES (?,?,?,?,?)",
                snapshot.lines().stream().map(row -> new Object[]{version, row.subwayId(), row.direction(), row.patternId(), row.lineName()}).toList());
        batch("INSERT INTO seoul_subway_station(data_version,station_id,station_name) VALUES (?,?,?)",
                snapshot.stations().stream().map(row -> new Object[]{version, row.stationId(), row.stationName()}).toList());
        batch("INSERT INTO seoul_subway_line_station(data_version,subway_id,direction,service_pattern_id,station_id,station_sequence) VALUES (?,?,?,?,?,?)",
                snapshot.lineStations().stream().map(row -> new Object[]{version, row.subwayId(), row.direction(), row.patternId(), row.stationId(), row.sequence()}).toList());
    }

    /** 전체 목록을 driver에 한 번에 넘기지 않고 고정 크기 배치로 잘라 MySQL에 전송한다. */
    private void batch(String sql, List<Object[]> rows) {
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            jdbcTemplate.batchUpdate(sql, rows.subList(start, Math.min(start + BATCH_SIZE, rows.size())));
        }
    }

    private Map<String, Integer> header(Row row, DataFormatter formatter) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            result.put(formatter.formatCellValue(row.getCell(index)).strip(), index);
        }
        return result;
    }

    private Map<String, Integer> header(List<String> headers) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            result.put(headers.get(index).strip(), index);
        }
        return result;
    }

    private void requireColumns(Map<String, Integer> columns, String... required) {
        for (String column : required) {
            if (!columns.containsKey(column)) {
                throw new IllegalArgumentException("마스터 원본에 필수 열이 없습니다: " + column);
            }
        }
    }

    private String cell(Row row, int column, DataFormatter formatter) {
        return formatter.formatCellValue(row.getCell(column)).strip();
    }

    private int integerCell(Row row, int column, DataFormatter formatter, int rowIndex) {
        try {
            return new BigDecimal(cell(row, column, formatter)).intValueExact();
        } catch (RuntimeException exception) {
            // 잘못된 순번을 0으로 대체하면 정류장 방향이 틀어지므로 원본 행 번호와 함께 적재를 중단한다.
            throw new IllegalArgumentException("버스 순번 오류: XLSX " + (rowIndex + 1) + "행", exception);
        }
    }

    private BigDecimal decimalCell(Row row, int column, DataFormatter formatter) {
        String value = cell(row, column, formatter);
        return value.isBlank() ? null : new BigDecimal(value);
    }

    /** 따옴표와 이중 따옴표를 처리하는 작은 RFC 4180 한 줄 파서다. 원본에는 줄바꿈 필드가 없다. */
    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("닫히지 않은 CSV 따옴표가 있습니다.");
        }
        values.add(value.toString());
        return values;
    }

    private String value(List<String> values, Map<String, Integer> columns, String name) {
        return values.get(columns.get(name)).strip();
    }

    /** 빈 문자열도 허용하는 DB NOT NULL보다 앞에서 topology 식별자의 의미 무결성을 강제한다. */
    private String requiredTopologyValue(String value, String column, int rowNumber) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "지하철 topology 필수값 누락: CSV " + rowNumber + "행, " + column);
        }
        return value.strip();
    }

    /** 실시간 crosswalk JSON의 필수 ID를 공백 제거 후 검증해 우회 중복을 막는다. */
    private String requiredMappingValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "지하철 실시간 ID 대응표에 필수값이 없습니다: " + field);
        }
        return value.strip();
    }

    private int timeOrder(String value) {
        String[] parts = value.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("지하철 운행시각 형식 오류: " + value);
        }
        // 24시 이후 값도 있으므로 LocalTime 대신 누적 초로 정렬한다.
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60
                + (parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
    }

    private List<TimetableStop> removeConsecutiveDuplicates(List<TimetableStop> values) {
        List<TimetableStop> result = new ArrayList<>();
        for (TimetableStop value : values) {
            if (result.isEmpty() || !Objects.equals(result.get(result.size() - 1).stationId(), value.stationId())) {
                result.add(value);
            }
        }
        return result;
    }

    private String subwayId(String lineNumber) {
        try {
            int line = Integer.parseInt(lineNumber);
            if (line < 1) {
                throw new IllegalArgumentException("지원하지 않는 서울 지하철 호선입니다: " + lineNumber);
            }
            // 실시간 subwayId를 추측하지 않고 원본 정적 호선 코드를 보존한다.
            return Integer.toString(line);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("지하철 호선 번호가 숫자가 아닙니다: " + lineNumber, exception);
        }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    private String requiredLine(BufferedReader reader, String label) throws Exception {
        String line = reader.readLine();
        if (line == null) {
            throw new IllegalArgumentException(label + "가 없습니다.");
        }
        return line;
    }

    private void requireReadable(Path path, String label) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(label + "을 읽을 수 없습니다: " + path);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 활성화된 마스터 버전과 테이블별 적재 행 수다.
     * 운영자는 원본 행 수와 이 값을 비교해 누락 여부를 확인하고 적재 로그의 검증 근거로 사용한다.
     */
    public record ImportSummary(
            String dataVersion,
            int busRouteCount,
            int busStopCount,
            int busRouteStopCount,
            int subwayPatternCount,
            int subwayStationCount,
            int subwayLineStationCount,
            int subwayRealtimeStationMappingCount,
            int transitAliasCount
    ) { }

    /** 프로젝트가 받는 검증된 정적 역 코드↔실시간 statnId JSON 한 행이다. */
    public record SubwayRealtimeStationRow(
            String lineNumber,
            String subwayId,
            String staticStationCode,
            String externalCode,
            String realtimeStationId
    ) { }

    /** 공백 제거와 파일 전체 유일성 검사를 통과해 DB 반영이 허용된 실시간 ID 대응 한 행이다. */
    private record ValidatedRealtimeStationRow(
            String staticLineId,
            String realtimeSubwayId,
            String staticStationCode,
            String externalCode,
            String realtimeStationId
    ) { }

    /** 검증 별칭 JSON 한 행. verified가 true인 행만 운영 자동 매칭에 사용된다. */
    public record TransitAliasRow(
            String provider,
            String mode,
            String aliasType,
            String aliasName,
            String canonicalId,
            String canonicalName,
            Boolean verified
    ) { }

    /** DB seoul_bus_route에 대응하는 중복 제거 전 노선 행이다. */
    private record BusRouteRow(String routeId, String routeName) { }

    /** DB seoul_bus_stop에 대응하며 좌표는 WGS84 경도·위도다. */
    private record BusStopRow(
            String stopId,
            String arsId,
            String stopName,
            BigDecimal longitude,
            BigDecimal latitude
    ) { }

    /** 한 노선 안 정류장 순서를 나타내며 sequence는 1 이상이어야 한다. */
    private record BusRouteStopRow(String routeId, String stopId, int sequence) { }

    /** 버스 파일 전체를 DB 세 테이블의 의미로 나눈 검증 단위다. */
    private record BusSnapshot(
            List<BusRouteRow> routes,
            List<BusStopRow> stops,
            List<BusRouteStopRow> routeStops
    ) { }

    /** 같은 실제 열차 운행의 역 순서를 합치기 위한 키이며 모든 필드가 필수다. */
    private record TrainKey(String lineNumber, String direction, String trainCode, String dayType) { }

    /** 자정을 넘긴 시각도 정렬 가능한 초 순서와 정적 역 ID를 보존한 시간표 정차 행이다. */
    private record TimetableStop(int timeOrder, String stationId, String stationName) { }

    /** 한 정적 호선의 방향·서비스 패턴 메타데이터다. patternId는 전체 역 순서의 해시다. */
    private record SubwayLineRow(String subwayId, String lineName, String direction, String patternId) { }

    /** 실시간 statnId가 아닌 변하지 않는 정적 역 코드와 공식 이름이다. */
    private record SubwayStationRow(String stationId, String stationName) { }

    /** 방향·서비스 패턴 안에서 정적 역의 순서를 나타낸다. */
    private record SubwayLineStationRow(
            String subwayId,
            String direction,
            String patternId,
            String stationId,
            int sequence
    ) { }

    /** 지하철 시간표 전체를 DB 세 테이블의 의미로 나눈 검증 단위다. */
    private record SubwaySnapshot(
            List<SubwayLineRow> lines,
            List<SubwayStationRow> stations,
            List<SubwayLineStationRow> lineStations
    ) { }
}
