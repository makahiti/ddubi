package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.service.MasterDataTransitMappingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 명시적으로 활성화한 관리 실행에서만 서울 지하철 마스터를 적재한다.
 *
 * <p>일반 Backend 기동에서는 절대 원본 파일을 읽거나 DB를 변경하지 않는다. 운영자가
 * {@code DDUBI_TRANSIT_MASTER_IMPORT_ENABLED=true}와 버전·지하철 시간표 경로를 제공한 경우에만
 * 한 번 적재하고, 커밋 후 Resolver 인덱스를 새 활성 버전으로 다시 구성한다.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "ddubi.transit-master-import",
        name = "enabled",
        havingValue = "true"
)
public class SeoulTransitMasterImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeoulTransitMasterImportRunner.class);

    private final SeoulTransitMasterImportService importService;
    private final MasterDataTransitMappingResolver mappingResolver;
    private final String dataVersion;
    private final String subwayTimetable;
    private final String subwayRealtimeStations;
    private final String transitAliases;

    /** 관리 적재 서비스, 인덱스 갱신 대상과 외부 주입 설정값을 보존한다. */
    public SeoulTransitMasterImportRunner(
            SeoulTransitMasterImportService importService,
            MasterDataTransitMappingResolver mappingResolver,
            @Value("${ddubi.transit-master-import.data-version:}") String dataVersion,
            @Value("${ddubi.transit-master-import.subway-timetable:}") String subwayTimetable,
            @Value("${ddubi.transit-master-import.subway-realtime-stations:}") String subwayRealtimeStations,
            @Value("${ddubi.transit-master-import.aliases:}") String transitAliases
    ) {
        this.importService = importService;
        this.mappingResolver = mappingResolver;
        this.dataVersion = dataVersion;
        this.subwayTimetable = subwayTimetable;
        this.subwayRealtimeStations = subwayRealtimeStations;
        this.transitAliases = transitAliases;
    }

    /** 파일 전체 적재가 커밋된 경우에만 실행 중 Matcher를 새 활성 버전으로 갱신한다. */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 배포 환경에 관리 플래그가 실수로 남아도 이미 로드한 같은 버전을 반복 INSERT하지 않는다.
        // 단, 현재는 원본 digest를 비교하지 않으므로 같은 version의 다른 파일 검증은 manifest 후속 과제다.
        if (dataVersion != null && dataVersion.equals(mappingResolver.loadedMasterVersion())) {
            // 관리 환경변수가 배포에 남아 있어도 이미 ACTIVE인 같은 버전을 재적재해 기동을 막지 않는다.
            log.info("서울 지하철 마스터 {} 버전이 이미 ACTIVE이므로 재적재를 건너뜁니다.", dataVersion);
            return;
        }
        // 서비스가 파일 전체를 하나의 트랜잭션으로 적재·활성화한다. 여기서는 DB 세부 작업을 나누지 않는다.
        SeoulTransitMasterImportService.ImportSummary summary = importService.importAndActivate(
                dataVersion,
                requiredPath(subwayTimetable, "subway-timetable"),
                optionalPath(subwayRealtimeStations),
                optionalPath(transitAliases)
        );
        // importAndActivate 트랜잭션이 커밋된 다음 호출되므로 새 활성 버전만 읽는다.
        mappingResolver.reload();
        log.info("서울 지하철 마스터 적재 완료: {}", summary);
    }

    private Path requiredPath(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "서울 마스터 적재 설정이 비었습니다: ddubi.transit-master-import." + property);
        }
        return Path.of(value);
    }

    /** 실시간 ID 대응표는 아직 확보되지 않은 환경도 기동할 수 있도록 선택 입력으로 둔다. */
    private Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
