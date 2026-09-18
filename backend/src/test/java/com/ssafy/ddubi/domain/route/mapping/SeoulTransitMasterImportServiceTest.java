package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

/**
 * 서울 교통 마스터가 잘못된 topology/crosswalk 입력을 ACTIVE로 전환하기 전에 거부하는지 검증한다.
 *
 * <p>실제 MS949 CSV·JSON 파서를 통과시켜 private helper만 직접 시험하는 것보다
 * 운영 적재 경계에 가까운 회귀를 만든다. JDBC는 mock으로 격리하며, 아래 실패들은 모든
 * 본 테이블 변경을 롤백시키는 {@code importAndActivate} 예외로 관찰한다.</p>
 */
class SeoulTransitMasterImportServiceTest {

    private static final Charset KOREAN_CSV = Charset.forName("MS949");

    @TempDir
    Path tempDir;

    private SeoulTransitMasterImportService service;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        // Mockito의 null COUNT 결과도 서비스에서 '기존 버전 없음'으로 취급된다. 두 회귀는 파일 전체
        // 검증 중 실패하므로 membership 조회나 UPDATE 반환값을 꾸며 실제 오류를 가리지 않는다.
        service = new SeoulTransitMasterImportService(jdbcTemplate, new ObjectMapper());
    }

    /** 버스 파일·테이블 없이도 지하철의 정적 topology를 적재할 수 있어야 한다. */
    @Test
    void importsSubwayWithoutReadingOrWritingLegacyBusMaster() throws Exception {
        var summary=service.importAndActivate("SUBWAY_ONLY",writeSubwayTimetable());
        assertThat(summary.subwayPatternCount()).isEqualTo(2);
        assertThat(summary.subwayStationCount()).isEqualTo(4);
        assertThat(summary.subwayLineStationCount()).isEqualTo(4);
        assertThat(mockingDetails(jdbcTemplate).getInvocations()).allSatisfy(call->{
            if(call.getArguments().length>0 && call.getArguments()[0] instanceof String sql)
                assertThat(sql).doesNotContain("seoul_bus_");
        });
    }

    /** BUS 전역 별칭을 실수로 기존 지하철 적재기에 넣어 무효한 검토 근거로 저장하지 못하게 한다. */
    @Test
    void rejectsBusAliasesInSubwayImporter() throws Exception {
        var aliases=tempDir.resolve("bus-alias.json");
        Files.writeString(aliases,"""
                [{"provider":"KAKAO","mode":"BUS","aliasType":"ROUTE","aliasName":"100",
                  "canonicalId":"R1","canonicalName":"100","verified":true}]
                """);
        assertThatThrownBy(()->service.importAndActivate("SUB_ONLY",writeSubwayTimetable(),null,aliases))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mode");
    }

    /** 핵심 식별자가 비면 서로 다른 열차 행이 같은 TrainKey로 합쳐지는 회귀를 차단한다. */
    @ParameterizedTest(name = "지하철 필수 topology 열 누락 거부: {0}")
    @MethodSource("requiredTopologyColumns")
    void rejectsBlankTopologyIdentityBeforeFakeTrainKeyCanBeCreated(
            String expectedColumn,
            int[] blankColumns
    ) throws Exception {
        Path subwayTimetable = writeSubwayTimetable(blankColumns);

        assertThatThrownBy(() -> service.importAndActivate(
                "blank-" + expectedColumn,
                subwayTimetable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지하철 topology 필수값 누락")
                .hasMessageContaining(expectedColumn);
    }

    /** 하나의 실시간 호선 ID가 두 정적 호선의 관측으로 분기되는 crosswalk 오염을 막는다. */
    @Test
    void rejectsRealtimeSubwayIdSharedByDifferentStaticLines() throws Exception {
        Path mapping = tempDir.resolve("realtime-stations.json");
        Files.writeString(mapping, """
                [
                  {
                    "lineNumber": "1",
                    "subwayId": "1001",
                    "staticStationCode": "0101",
                    "externalCode": "101",
                    "realtimeStationId": "1000001"
                  },
                  {
                    "lineNumber": "2",
                    "subwayId": "1001",
                    "staticStationCode": "0201",
                    "externalCode": "201",
                    "realtimeStationId": "2000001"
                  }
                ]
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.importAndActivate(
                "realtime-line-conflict",
                writeSubwayTimetable(),
                mapping,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("한 실시간 subwayId가 서로 다른 정적 호선에 연결됩니다")
                .hasMessageContaining("1001")
                .hasMessageContaining("1,2");
    }

    private static Stream<Arguments> requiredTopologyColumns() {
        return Stream.of(
                Arguments.of("호선", new int[]{0}),
                Arguments.of("역사코드", new int[]{1}),
                Arguments.of("역사명", new int[]{2}),
                Arguments.of("주중주말", new int[]{3}),
                Arguments.of("방향", new int[]{4}),
                Arguments.of("급행여부", new int[]{5}),
                Arguments.of("열차코드", new int[]{6}),
                // 도착·출발 중 하나는 fallback 가능하므로 둘이 동시에 비었을 때만 실패해야 한다.
                Arguments.of("열차도착시간/열차출발시간", new int[]{7, 8})
        );
    }

    /** 1·2호선에 각각 두 역이 있는 정상 입력에서 지정 열만 비운 MS949 CSV를 만든다. */
    private Path writeSubwayTimetable(int... blankColumns) throws Exception {
        Path path = tempDir.resolve("subway-" + System.nanoTime() + ".csv");
        List<String> firstRow = new ArrayList<>(List.of(
                "1", "0101", "종각", "평일", "상행", "0", "T1", "05:00:00", "05:00:30"));
        for (int column : blankColumns) {
            firstRow.set(column, "");
        }
        String csv = String.join(",", List.of(
                "호선", "역사코드", "역사명", "주중주말", "방향", "급행여부",
                "열차코드", "열차도착시간", "열차출발시간")) + "\n"
                + String.join(",", firstRow) + "\n"
                + "1,0102,시청,평일,상행,0,T1,05:02:00,05:02:30\n"
                + "2,0201,시청,평일,상행,0,T2,05:10:00,05:10:30\n"
                + "2,0202,을지로입구,평일,상행,0,T2,05:12:00,05:12:30\n";
        Files.writeString(path, csv, KOREAN_CSV);
        return path;
    }

}
