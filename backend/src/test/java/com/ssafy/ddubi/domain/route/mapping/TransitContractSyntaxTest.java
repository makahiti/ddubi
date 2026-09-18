package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 매핑 변경과 함께 수정되는 YAML/JSON 기계 계약이 문법 오류로 배포되지 않게 한다. */
class TransitContractSyntaxTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesAllYamlAndEventJsonContracts() throws Exception {
        Path contracts = locateContracts();
        try (Stream<Path> yamlFiles = Files.list(contracts)) {
            for (Path path : yamlFiles.filter(file -> file.toString().endsWith(".yaml")).toList()) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Object parsed = new Yaml().load(reader);
                    assertThat(parsed).as(path.toString()).isNotNull();
                }
            }
        }
        try (Stream<Path> jsonFiles = Files.list(contracts.resolve("events"))) {
            for (Path path : jsonFiles.filter(file -> file.toString().endsWith(".json")).toList()) {
                objectMapper.readTree(path.toFile());
            }
        }
    }

    /**
     * Redis 문서의 채번 예시와 실제 Factory가 달라지면 단순 YAML 문법 검사를 통과해도
     * Spark key와 Backend key가 갈라진다. 대표 BUS/SUBWAY 예시를 코드 출력과 직접 대조한다.
     */
    @Test
    void redisSegmentIdContractMatchesCanonicalFactory() throws Exception {
        Path contract = locateContracts().resolve("redis-keys.yaml");
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(contract)) {
            root = new Yaml().load(reader);
        }

        Map<String, Object> segmentId = child(root, "segmentId");
        Map<String, Object> modes = child(segmentId, "modes");
        Map<String, Object> bus = child(modes, "BUS");
        Map<String, Object> subway = child(modes, "SUBWAY");
        CanonicalSegmentIdFactory factory = new CanonicalSegmentIdFactory();

        assertThat(bus.get("example")).isEqualTo(factory.create(
                com.ssafy.ddubi.domain.route.model.TransportMode.BUS,
                "100100609", "123456", "123789"));
        assertThat(subway.get("example")).isEqualTo(factory.create(
                com.ssafy.ddubi.domain.route.model.TransportMode.SUBWAY,
                "2", "0222", "0223"));
        assertThat(segmentId.get("computedV1MaxLength")).isEqualTo(156);
        assertThat(segmentId.get("contractVersion")).isEqualTo(CanonicalSegmentIdFactory.BUS_POLICY);
        assertThat(bus.get("regionalExample")).isEqualTo(factory.createBus("GBIS","1","10","11"));
    }

    /** 인천의 긴 namespace와 선행 0이 기준선 업로드에서도 보존되고 구성요소 초과/임의 추가 콜론은 거절되는지 확인한다. */
    @Test void baselineValidationAcceptsCanonicalRegionsAndRejectsMalformedComponents() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(String id:java.util.List.of("BUS:1:10:11","BUS:GBIS.1:GBIS.010:GBIS.011",
                    "BUS:INCHEON_PUBLIC_CSV.1:INCHEON_PUBLIC_CSV.10:INCHEON_PUBLIC_CSV.11","SUB:2:0222:0223")) {
                assertThat(validator.validate(baseline(id))).as(id).isEmpty();
            }
            for(String id:java.util.List.of("BUS:GBIS:1:10:11","BUS:"+"a".repeat(51)+":10:11","BUS:1:출발:도착")) {
                assertThat(validator.validate(baseline(id))).as(id).isNotEmpty();
            }
        }
    }
    private com.ssafy.ddubi.domain.pipeline.dto.request.BaselineRowsRequest.Row baseline(String id) {
        return new com.ssafy.ddubi.domain.pipeline.dto.request.BaselineRowsRequest.Row(id,1,9,
                com.ssafy.ddubi.domain.pipeline.entity.WeatherBucket.DRY,30,40,50,10);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as("필수 YAML 객체: " + key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** Maven을 저장소 루트 또는 backend 폴더에서 실행하는 두 경우를 모두 지원한다. */
    private Path locateContracts() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path fromRepositoryRoot = workingDirectory.resolve("docs/contracts");
        if (Files.isDirectory(fromRepositoryRoot)) {
            return fromRepositoryRoot;
        }
        Path fromBackend = workingDirectory.resolve("../docs/contracts").normalize();
        if (Files.isDirectory(fromBackend)) {
            return fromBackend;
        }
        throw new IllegalStateException("docs/contracts 경로를 찾을 수 없습니다: " + workingDirectory);
    }
}
