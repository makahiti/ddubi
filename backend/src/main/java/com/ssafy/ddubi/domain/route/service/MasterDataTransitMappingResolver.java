package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.mapping.BusMappingRequest;
import com.ssafy.ddubi.domain.route.mapping.BusMappingResult;
import com.ssafy.ddubi.domain.route.mapping.BusRouteMaster;
import com.ssafy.ddubi.domain.route.mapping.BusSegmentMatcher;
import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.mapping.SubwayLineMaster;
import com.ssafy.ddubi.domain.route.mapping.SubwayMappingRequest;
import com.ssafy.ddubi.domain.route.mapping.SubwayMappingResult;
import com.ssafy.ddubi.domain.route.mapping.SubwaySegmentMatcher;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import com.ssafy.ddubi.domain.route.mapping.SeoulTransitMasterDataProvider;
import com.ssafy.ddubi.domain.route.mapping.CanonicalSegmentIdFactory;
import com.ssafy.ddubi.domain.route.mapping.ProviderSegmentMappingStore;
import com.ssafy.ddubi.domain.route.mapping.ProviderSegmentMappingLookup;
import com.ssafy.ddubi.domain.route.mapping.ProviderSegmentSignatureFactory;
import com.ssafy.ddubi.domain.route.mapping.TransitAliasSnapshot;
import com.ssafy.ddubi.domain.route.mapping.TransitNameNormalizer;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 이미 적재된 서울 버스·지하철 마스터를 사용해 실제 매칭 알고리즘을 실행한다.
 *
 * <p>생성 시 마스터를 Matcher의 이름 인덱스로 바꾸므로 요청마다 전체 테이블을 다시
 * 순회하지 않는다. 따라서 DB 구현은 애플리케이션 시작 또는 마스터 버전 교체 시점에
 * 읽기 모델을 만들어 이 클래스에 주입하면 된다. 실제 요청에서는 마스터 버전별 MySQL 캐시를
 * 먼저 조회하고 miss일 때만 순수 Matcher를 실행한다. 캐시 여부와 관계없이 같은 마스터와
 * 같은 요청에는 항상 같은 판정과 segment_id를 반환한다.</p>
 */
@Component
@DependsOnDatabaseInitialization
public class MasterDataTransitMappingResolver implements TransitMappingResolver {

    private static final Logger log = LoggerFactory.getLogger(MasterDataTransitMappingResolver.class);
    private static final String MAPPING_POLICY_VERSION = "transit-v4";

    private final SeoulTransitMasterDataProvider provider;
    private final ProviderSegmentMappingStore mappingStore;
    private final ProviderSegmentSignatureFactory signatureFactory;
    private final CanonicalSegmentIdFactory segmentIdFactory;
    private final double busMaxEndpointDistanceMeters;
    private volatile MappingSnapshot snapshot;

    /** Spring 실행 환경에서는 MySQL Provider의 활성 마스터 스냅샷을 한 번 읽어 인덱싱한다. */
    @Autowired
    public MasterDataTransitMappingResolver(
            SeoulTransitMasterDataProvider provider,
            ProviderSegmentMappingStore mappingStore,
            ProviderSegmentSignatureFactory signatureFactory,
            CanonicalSegmentIdFactory segmentIdFactory,
            @Value("${ddubi.transit-mapping.bus.max-endpoint-distance-meters:150}")
            double busMaxEndpointDistanceMeters
    ) {
        this.provider = provider;
        this.mappingStore = mappingStore;
        this.signatureFactory = signatureFactory;
        this.segmentIdFactory = segmentIdFactory;
        this.busMaxEndpointDistanceMeters = busMaxEndpointDistanceMeters;
        reload();
    }

    /** 테스트·Replay에서는 외부 DB 없이 고정된 마스터 컬렉션을 직접 주입한다. */
    public MasterDataTransitMappingResolver(
            Collection<BusRouteMaster> busRoutes,
            Collection<SubwayLineMaster> subwayLines
    ) {
        this.provider = null;
        this.mappingStore = null;
        this.signatureFactory = new ProviderSegmentSignatureFactory();
        this.segmentIdFactory = new CanonicalSegmentIdFactory();
        this.busMaxEndpointDistanceMeters = BusSegmentMatcher.DEFAULT_MAX_ENDPOINT_DISTANCE_METERS;
        TransitNameNormalizer normalizer = new TransitNameNormalizer();
        this.snapshot = new MappingSnapshot(
                "TEST",
                mappingPolicyVersion(TransitAliasSnapshot.empty(), busMaxEndpointDistanceMeters),
                normalizer,
                new BusSegmentMatcher(busRoutes, normalizer),
                new SubwaySegmentMatcher(subwayLines, normalizer));
    }

    /**
     * 활성 버전 교체 후 두 Matcher를 새 스냅샷으로 함께 바꾼다.
     * 새 객체를 먼저 완성한 뒤 volatile 참조를 교체하므로 요청이 반쯤 만들어진 인덱스를 보지 않는다.
     */
    public synchronized void reload() {
        if (provider == null) {
            return;
        }
        String newVersion = provider.activeVersion().orElse("");
        if (newVersion.isBlank()) {
            TransitNameNormalizer emptyNormalizer = new TransitNameNormalizer();
            this.snapshot = new MappingSnapshot(
                    "", mappingPolicyVersion(
                            TransitAliasSnapshot.empty(), busMaxEndpointDistanceMeters),
                    emptyNormalizer,
                    new BusSegmentMatcher(List.of(), emptyNormalizer),
                    new SubwaySegmentMatcher(List.of(), emptyNormalizer));
            return;
        }
        TransitAliasSnapshot aliases = provider.loadAliases(newVersion);
        TransitNameNormalizer newNormalizer = new TransitNameNormalizer();
        BusSegmentMatcher newBusMatcher = new BusSegmentMatcher(
                provider.loadBusRoutes(newVersion), newNormalizer,
                aliases.busRouteAliasIds(), aliases.busStopAliasIds(),
                busMaxEndpointDistanceMeters);
        SubwaySegmentMatcher newSubwayMatcher = new SubwaySegmentMatcher(
                provider.loadSubwayLines(newVersion), newNormalizer,
                aliases.subwayLineAliasIds(), aliases.subwayStationAliasIds());
        // 버전·별칭·두 Matcher를 한 참조로 교체해 요청이 서로 다른 버전을 섞어 보지 않게 한다.
        this.snapshot = new MappingSnapshot(
                newVersion,
                mappingPolicyVersion(aliases, busMaxEndpointDistanceMeters),
                newNormalizer,
                newBusMatcher,
                newSubwayMatcher);
    }

    /**
     * 다른 Backend 인스턴스가 새 마스터를 활성화해도 재시작 없이 같은 버전으로 수렴한다.
     * 매 요청마다 수만 행을 읽지 않고 활성 포인터와 작은 별칭 스냅샷만 주기적으로 확인한다.
     * 새 immutable dataVersion 또는 같은 버전의 별칭 검수 변경이 감지된 때에만 전체
     * 인덱스를 원자 교체한다.
     */
    @Scheduled(fixedDelayString = "${ddubi.transit-mapping.refresh-interval-ms:60000}")
    public void reloadWhenActiveVersionChanges() {
        if (provider == null) {
            return;
        }
        try {
            String activeVersion = provider.activeVersion().orElse("");
            MappingSnapshot current = snapshot;
            boolean versionChanged = !Objects.equals(activeVersion, current.version());
            boolean aliasesChanged = false;
            if (!versionChanged && !activeVersion.isBlank()) {
                TransitAliasSnapshot activeAliases = provider.loadAliases(activeVersion);
                aliasesChanged = !Objects.equals(
                        mappingPolicyVersion(activeAliases, busMaxEndpointDistanceMeters),
                        current.cachePolicyVersion());
            }
            if (versionChanged || aliasesChanged) {
                reload();
            }
        } catch (RuntimeException exception) {
            // 이미 메모리에 적재된 정상 스냅샷을 버리지 않고 다음 주기에 다시 확인한다.
            log.warn("서울 교통 마스터 활성 버전 확인 실패로 기존 스냅샷을 유지합니다.", exception);
        }
    }

    /** 운영 health/metric 어댑터가 현재 프로세스의 로드 버전을 확인할 수 있는 읽기 전용 값이다. */
    public String loadedMasterVersion() {
        return snapshot.version();
    }

    /**
     * 교통수단별 Matcher로 위임하고 서로 다른 결과 타입을 공통 응답으로 변환한다.
     * 입력 이름·순서·좌표에는 단위 변환을 적용하지 않으며 EXACT 외 결과의 인접 구간은 비어 있다.
     */
    @Override
    public TransitMappingResult resolve(SegmentResolveRequest request) {
        // 1단계: WALK는 서울 대중교통 topology의 대상이 아니며 별도 정책이 정해질 때까지 ID를 만들지 않는다.
        if (request.mode() == TransportMode.WALK) {
            return TransitMappingResult.notApplicable();
        }

        // 2단계: 한 요청은 시작 시점의 immutable 스냅샷 하나만 고정해 사용한다.
        // reload가 동시에 발생해도 BUS Matcher와 정책 버전이 서로 다른 세대로 섞이지 않는다.
        MappingSnapshot current = snapshot;
        String version = current.version();
        if (version.isBlank()) {
            // 마스터 미구성과 단순 이름 미매핑을 구분해야 운영자가 적재 장애를 즉시 발견할 수 있다.
            return TransitMappingResult.masterDataNotConfigured();
        }
        // 3단계: 이름·전체 순서·BUS endpoint를 서명해 같은 증거의 판정을 재사용한다.
        // 마스터와 정책 버전은 DB 복합키의 별도 열이므로 정책 변경 뒤 과거 EXACT가 섞이지 않는다.
        String signature = signatureFactory.create(request, current.normalizer());
        if (mappingStore != null && !version.isBlank()) {
            try {
                ProviderSegmentMappingLookup cached = mappingStore.find(
                        "KAKAO", request.mode(), signature, version, current.cachePolicyVersion());
                if (cached.status() == ProviderSegmentMappingLookup.Status.EXACT) {
                    return cached.exactResult();
                }
                if (cached.status() == ProviderSegmentMappingLookup.Status.REJECTED) {
                    // 사람의 오판 판정을 일반 cache miss로 취급해 같은 EXACT를 재계산하지 않는다.
                    return TransitMappingResult.manuallyRejected(
                            version, current.cachePolicyVersion());
                }
            } catch (RuntimeException exception) {
                // 마스터는 이미 메모리에 있으므로 캐시 장애를 사용자 경로 검색 장애로 확대하지 않는다.
                log.warn("교통 매핑 캐시 조회 실패로 메모리 Matcher를 사용합니다: signature={}",
                        signature, exception);
            }
        }

        // 4단계: cache miss일 때만 순수 Matcher를 실행한다. Matcher는 EXACT 외에는 edge를 발급하지 않는다.
        TransitMappingResult result = switch (request.mode()) {
            case BUS -> mapBus(current, current.busMatcher().match(new BusMappingRequest(
                    request.vehicleNames(),
                    request.fromName(),
                    request.toName(),
                    request.stopNames(),
                    request.geometry()
            )));
            case SUBWAY -> mapSubway(current, current.subwayMatcher().match(new SubwayMappingRequest(
                    request.vehicleNames(),
                    request.fromName(),
                    request.toName(),
                    request.stopNames()
            )));
            case WALK -> TransitMappingResult.notApplicable();
        };
        // 5단계: 성공·실패를 모두 저장해 품질 통계를 남기되, 조회 재사용은 검증된 EXACT만 허용한다.
        if (mappingStore != null && !version.isBlank()) {
            try {
                mappingStore.save(
                        "KAKAO", signature, version, current.cachePolicyVersion(), request, result);
            } catch (RuntimeException exception) {
                // 계산 결과는 유효하므로 반환하고, 저장 실패는 로그·DB 모니터링으로 별도 복구한다.
                log.warn("교통 매핑 캐시 저장 실패를 무시하고 계산 결과를 반환합니다: signature={}",
                        signature, exception);
            }
        }
        return result;
    }

    /**
     * 버스 전용 결과의 공식 인접 stopId 쌍을 공용 segment_id와 실시간 조회 필드로 변환한다.
     * 서울 버스 공식 routeId/stopId는 현재 실시간 API에서도 쓰므로 canonical과 realtime 필드가 같다.
     */
    private TransitMappingResult mapBus(MappingSnapshot current, BusMappingResult result) {
        return new TransitMappingResult(
                TransitMappingStatus.valueOf(result.status().name()),
                current.version(),
                current.cachePolicyVersion(),
                result.officialRouteId(),
                result.officialRouteName(),
                result.fromStopId(),
                result.toStopId(),
                result.startOrder(),
                result.endOrder(),
                result.direction(),
                result.matchScore(),
                result.passingMatchRatio(),
                result.candidateCount(),
                result.failureReason(),
                null,
                result.segments().stream()
                        .map(segment -> new MappedTransitSegment(
                                segment.sequence(),
                                segmentIdFactory.create(
                                        TransportMode.BUS,
                                        segment.routeId(),
                                        segment.fromStopId(),
                                        segment.toStopId()),
                                TransportMode.BUS,
                                segment.routeId(),
                                segment.routeId(),
                                result.officialRouteName(),
                                result.direction(),
                                segment.fromStopId(),
                                segment.toStopId(),
                                segment.fromStopId(),
                                segment.toStopId(),
                                segment.fromOrder(),
                                segment.toOrder()
                        ))
                        .toList()
        );
    }

    /**
     * 지하철 topology의 정적 subwayId/stationId로 segment_id를 만들고 실시간 ID는 별도 필드에 둔다.
     * 실시간 대응표가 없다고 정적 ID를 추측해 채우지 않으며 coverage 계산이 NONE/PARTIAL로 드러낸다.
     */
    private TransitMappingResult mapSubway(MappingSnapshot current, SubwayMappingResult result) {
        return new TransitMappingResult(
                TransitMappingStatus.valueOf(result.status().name()),
                current.version(),
                current.cachePolicyVersion(),
                result.officialSubwayId(),
                result.officialLineName(),
                result.fromStationId(),
                result.toStationId(),
                result.startOrder(),
                result.endOrder(),
                result.direction(),
                result.matchScore(),
                result.passingMatchRatio(),
                result.candidateCount(),
                result.failureReason(),
                null,
                result.segments().stream()
                        .map(segment -> new MappedTransitSegment(
                                segment.sequence(),
                                segmentIdFactory.create(
                                        TransportMode.SUBWAY,
                                        segment.subwayId(),
                                        segment.fromStationId(),
                                        segment.toStationId()),
                                TransportMode.SUBWAY,
                                segment.subwayId(),
                                segment.realtimeSubwayId(),
                                segment.lineName(),
                                segment.direction(),
                                segment.fromStationId(),
                                segment.toStationId(),
                                segment.fromRealtimeStationId(),
                                segment.toRealtimeStationId(),
                                segment.fromOrder(),
                                segment.toOrder()
                        ))
                        .toList()
        );
    }

    /**
     * 캐시 정책 버전은 알고리즘 세대뿐 아니라 별칭 스냅샷과 BUS 좌표 허용 반경도 포함한다.
     * 반경을 150m에서 50m로 강화한 뒤 이전 EXACT를 재사용하면 현재 정책을 우회하므로,
     * 정책 입력을 SHA-256 지문으로 묶어 설정 변경 자체가 캐시 miss를 만들게 한다.
     */
    private static String mappingPolicyVersion(
            TransitAliasSnapshot aliases,
            double busMaxEndpointDistanceMeters
    ) {
        String canonicalDistance = BigDecimal.valueOf(busMaxEndpointDistanceMeters)
                .stripTrailingZeros()
                .toPlainString();
        String policyInputs = aliases.policyFingerprint()
                + "|busMaxEndpointDistanceMeters=" + canonicalDistance;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(policyInputs.getBytes(StandardCharsets.UTF_8));
            // DB 계약 VARCHAR(30) 안에서 64-bit 지문을 보존한다(접두사 포함 총 27자).
            return MAPPING_POLICY_VERSION + '-' + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            // 모든 Java 런타임이 SHA-256을 제공하므로 환경 손상 외에는 발생할 수 없다.
            throw new IllegalStateException("매핑 정책 버전 SHA-256 생성에 실패했습니다.", exception);
        }
    }

    /** 한 요청이 반드시 같은 마스터 버전·별칭·Matcher 조합만 보도록 묶은 불변 스냅샷이다. */
    private record MappingSnapshot(
            String version,
            String cachePolicyVersion,
            TransitNameNormalizer normalizer,
            BusSegmentMatcher busMatcher,
            SubwaySegmentMatcher subwayMatcher
    ) { }
}
