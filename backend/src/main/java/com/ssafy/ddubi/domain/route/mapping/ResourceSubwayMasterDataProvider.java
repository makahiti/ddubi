package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 검증된 수도권 지하철 JSON.gz 스냅샷을 기존 Resolver에 공급하는 읽기 전용 어댑터다.
 * subway-master-resource 미설정 시 기존 JDBC 활성 마스터를 그대로 사용한다.
 * 설정한 파일이 손상되면 기동을 실패시켜 잘못된 마스터나 빈 성공 결과로 대체하지 않는다.
 * 파일은 시작 때 한 번 읽으며 수정 후 재시작해야 한다. DB·Redis·Kafka에 쓰지 않는다.
 * 정적 ID와 실시간 crosswalk는 파일의 서로 다른 필드이며 서로 대신 사용할 수 없다.
 */
@Component
@Primary
public class ResourceSubwayMasterDataProvider implements SeoulTransitMasterDataProvider {
    private final SeoulTransitMasterDataProvider fallback;
    private final Snapshot snapshot;
    private final byte[] artifact;

    /** 명시적인 로컬/classpath 리소스만 허용하며 원격 URL을 요청 경로에서 읽지 않는다. */
    public ResourceSubwayMasterDataProvider(JdbcSeoulTransitMasterDataProvider fallback,
            ObjectMapper mapper, ResourceLoader loader,
            @Value("${ddubi.transit-mapping.subway-master-resource:}") String resource) throws IOException {
        this.fallback = fallback;
        if (resource == null || resource.isBlank()) {
            this.snapshot = null;
            this.artifact = null;
            return;
        }
        if (!resource.startsWith("classpath:") && !resource.startsWith("file:")) {
            throw new IllegalArgumentException("지하철 마스터는 classpath: 또는 file: 리소스여야 합니다.");
        }
        // 압축 및 해제 크기를 모두 제한한다. 반환 파일은 기동 시 검증한 원본 바이트와 동일하다.
        try (var input = loader.getResource(resource).getInputStream()) {
            this.artifact = input.readNBytes(8 * 1024 * 1024 + 1);
        }
        if (artifact.length > 8 * 1024 * 1024) throw new IOException("지하철 마스터 압축 크기 초과");
        try (var gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(artifact))) {
            byte[] decoded = gzip.readNBytes(32 * 1024 * 1024 + 1);
            if (decoded.length > 32 * 1024 * 1024) throw new IOException("지하철 마스터 해제 크기 초과");
            this.snapshot = mapper.readValue(decoded, Snapshot.class);
        }
        validate(snapshot);
    }

    /** Spark 배포에 쓰는 검증된 원본 gzip을 반환한다. 호출자가 내부 캐시를 변경할 수 없다. */
    public Optional<byte[]> artifact() { return artifact == null ? Optional.empty() : Optional.of(artifact.clone()); }

    /** 시작 시 완전히 검증된 파일 버전 또는 기존 DB의 ACTIVE 버전을 반환한다. */
    @Override
    public Optional<String> activeVersion() {
        return snapshot == null ? fallback.activeVersion() : Optional.of(snapshot.dataVersion());
    }

    /** 요청 버전이 다르면 파일 행을 섞지 않고 실패한다. 요청별 파일 I/O와 캐시는 없다. */
    @Override
    public List<SubwayLineMaster> loadSubwayLines(String dataVersion) {
        if (snapshot == null) return fallback.loadSubwayLines(dataVersion);
        requireVersion(dataVersion);
        return snapshot.lines();
    }

    /** 검증된 정적 ID를 가리키는 별칭만 반환한다. 별칭은 전체 이름 인덱스를 치환하지 않는다. */
    @Override
    public TransitAliasSnapshot loadAliases(String dataVersion) {
        if (snapshot == null) return fallback.loadAliases(dataVersion);
        requireVersion(dataVersion);
        return snapshot.aliases();
    }

    private void requireVersion(String version) {
        if (!snapshot.dataVersion().equals(version)) {
            throw new IllegalArgumentException("지하철 마스터 버전 불일치");
        }
    }

    /**
     * 1. ID/버전 계약 → 2. 패턴·순번 중복 → 3. 별칭 참조를 검사한다.
     * 유효하지 않은 행만 버리면 원본에 없던 이웃 역이 만들어지므로 파일 전체를 거절한다.
     * 방향·인접성의 출처 검증은 생성기의 책임이며 런타임은 파일 순번을 재추정하지 않는다.
     */
    private static void validate(Snapshot value) {
        if (value == null || value.schemaVersion() != 1 || value.dataVersion() == null
                || !value.dataVersion().matches("[A-Za-z0-9._-]{1,50}")
                || value.lines() == null || value.lines().isEmpty() || value.aliases() == null) {
            throw new IllegalArgumentException("지하철 마스터 스키마/버전/행 누락");
        }
        Set<String> patterns = new HashSet<>(), lineIds = new HashSet<>(), stationIds = new HashSet<>();
        var factory = new CanonicalSegmentIdFactory();
        for (var line : value.lines()) {
            if (line.lineName() == null || line.lineName().isBlank() || line.direction() == null
                    || line.direction().isBlank() || line.servicePatternId() == null || line.servicePatternId().isBlank()
                    || line.stations().size() < 2
                    || !patterns.add(line.subwayId()+":"+line.direction()+":"+line.servicePatternId())) {
                throw new IllegalArgumentException("지하철 운행 패턴 누락/중복");
            }
            lineIds.add(line.subwayId());
            int expected = 1;
            for (var station : line.stations()) {
                if (station.sequence() != expected++ || station.stationName() == null || station.stationName().isBlank()) {
                    throw new IllegalArgumentException("지하철 역명 누락 또는 순번 불연속");
                }
                factory.create(com.ssafy.ddubi.domain.route.model.TransportMode.SUBWAY,
                        line.subwayId(), station.stationId(), station.stationId());
                stationIds.add(station.stationId());
            }
        }
        if (value.aliases().subwayLineAliasIds().values().stream().anyMatch(ids -> !lineIds.containsAll(ids))
                || value.aliases().subwayStationAliasIds().values().stream().anyMatch(ids -> !stationIds.containsAll(ids))) {
            throw new IllegalArgumentException("지하철 별칭의 정적 ID 참조 누락");
        }
    }

    /** 파일 전용 스키마. 버전은 내용 해시에서 생성하며 불변 행과 별칭을 같은 단위로 제공한다. */
    public record Snapshot(int schemaVersion, String dataVersion,
                           List<SubwayLineMaster> lines, TransitAliasSnapshot aliases) {
        public Snapshot {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }
}
