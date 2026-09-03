package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.mapping.BusRouteMaster;
import com.ssafy.ddubi.domain.route.mapping.BusStopMaster;
import com.ssafy.ddubi.domain.route.mapping.SubwayLineMaster;
import com.ssafy.ddubi.domain.route.mapping.SubwayStationMaster;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import com.ssafy.ddubi.domain.route.mapping.RealtimeMappingCoverage;
import com.ssafy.ddubi.domain.route.mapping.CanonicalSegmentIdFactory;
import com.ssafy.ddubi.domain.route.mapping.ProviderSegmentMappingStore;
import com.ssafy.ddubi.domain.route.mapping.ProviderSegmentMappingLookup;
import com.ssafy.ddubi.domain.route.mapping.ProviderSegmentSignatureFactory;
import com.ssafy.ddubi.domain.route.mapping.SeoulTransitMasterDataProvider;
import com.ssafy.ddubi.domain.route.mapping.TransitAliasSnapshot;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.model.VehicleInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MasterDataTransitMappingResolverTest {

    @Test
    void reusesCachedResultWithinSameActiveMasterVersion() {
        BusRouteMaster master = route("R1", "100", "A1", "A2");
        InMemoryMappingStore store = new InMemoryMappingStore();
        SeoulTransitMasterDataProvider provider = new SeoulTransitMasterDataProvider() {
            @Override public Optional<String> activeVersion() { return Optional.of("V1"); }
            @Override public List<BusRouteMaster> loadBusRoutes(String version) { return List.of(master); }
            @Override public List<SubwayLineMaster> loadSubwayLines(String version) { return List.of(); }
            @Override public TransitAliasSnapshot loadAliases(String version) { return TransitAliasSnapshot.empty(); }
        };
        MasterDataTransitMappingResolver resolver = new MasterDataTransitMappingResolver(
                provider, store, new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(), 150.0);
        SegmentResolveRequest request = new SegmentResolveRequest(
                TransportMode.BUS, "100", "출발", "도착", List.of("출발", "도착"),
                List.of(new VehicleInfo("100", "BUS")),
                List.of(new Coordinate(127.0, 37.0), new Coordinate(127.01, 37.01)));

        TransitMappingResult first = resolver.resolve(request);
        TransitMappingResult second = resolver.resolve(request);

        assertThat(first.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(second).isEqualTo(first);
        assertThat(store.saveCount).isEqualTo(1);
        assertThat(store.findCount).isEqualTo(2);
    }

    @Test
    void doesNotReuseExactCacheAfterBusEndpointThresholdChanges() {
        BusRouteMaster master = route("R1", "100", "A1", "A2");
        InMemoryMappingStore store = new InMemoryMappingStore();
        SeoulTransitMasterDataProvider provider = new SeoulTransitMasterDataProvider() {
            @Override public Optional<String> activeVersion() { return Optional.of("V1"); }
            @Override public List<BusRouteMaster> loadBusRoutes(String version) { return List.of(master); }
            @Override public List<SubwayLineMaster> loadSubwayLines(String version) { return List.of(); }
            @Override public TransitAliasSnapshot loadAliases(String version) { return TransitAliasSnapshot.empty(); }
        };
        SegmentResolveRequest request = new SegmentResolveRequest(
                TransportMode.BUS, "100", "출발", "도착", List.of("출발", "도착"),
                List.of(new VehicleInfo("100", "BUS")),
                // 첫 점은 승차 정류장에서 약 89m: 150m 정책은 통과하고 50m 정책은 실패한다.
                List.of(new Coordinate(127.001, 37.0), new Coordinate(127.01, 37.01)));

        MasterDataTransitMappingResolver radius150 = new MasterDataTransitMappingResolver(
                provider, store, new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(), 150.0);
        MasterDataTransitMappingResolver radius50 = new MasterDataTransitMappingResolver(
                provider, store, new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(), 50.0);

        TransitMappingResult first = radius150.resolve(request);
        TransitMappingResult second = radius50.resolve(request);

        assertThat(first.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(second.status()).isEqualTo(TransitMappingStatus.PARTIAL);
        assertThat(second.failureReason()).isEqualTo("GEOMETRY_ENDPOINT_TOO_FAR");
        assertThat(second.mappingPolicyVersion()).isNotEqualTo(first.mappingPolicyVersion());
        assertThat(store.saveCount).isEqualTo(2);
    }

    @Test
    void doesNotRecalculateOrServeMappingThatWasManuallyRejected() {
        BusRouteMaster master = route("R1", "100", "A1", "A2");
        InMemoryMappingStore store = new InMemoryMappingStore();
        store.rejected = true;
        SeoulTransitMasterDataProvider provider = new SeoulTransitMasterDataProvider() {
            @Override public Optional<String> activeVersion() { return Optional.of("V1"); }
            @Override public List<BusRouteMaster> loadBusRoutes(String version) { return List.of(master); }
            @Override public List<SubwayLineMaster> loadSubwayLines(String version) { return List.of(); }
            @Override public TransitAliasSnapshot loadAliases(String version) { return TransitAliasSnapshot.empty(); }
        };
        MasterDataTransitMappingResolver resolver = new MasterDataTransitMappingResolver(
                provider, store, new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(), 150.0);

        TransitMappingResult result = resolver.resolve(new SegmentResolveRequest(
                TransportMode.BUS, "100", "출발", "도착", List.of("출발", "도착"),
                List.of(new VehicleInfo("100", "BUS")),
                List.of(new Coordinate(127.0, 37.0), new Coordinate(127.01, 37.01))));

        assertThat(result.status()).isEqualTo(TransitMappingStatus.UNMAPPED);
        assertThat(result.failureReason()).isEqualTo("MANUALLY_REJECTED");
        assertThat(result.segments()).isEmpty();
        assertThat(store.saveCount).isZero();
    }

    @Test
    void reloadsVerifiedAliasesEvenWhenActiveMasterVersionDoesNotChange() {
        BusRouteMaster master = route("R1", "100", "A1", "A2");
        class MutableAliasProvider implements SeoulTransitMasterDataProvider {
            private TransitAliasSnapshot aliases = TransitAliasSnapshot.empty();
            @Override public Optional<String> activeVersion() { return Optional.of("V1"); }
            @Override public List<BusRouteMaster> loadBusRoutes(String version) { return List.of(master); }
            @Override public List<SubwayLineMaster> loadSubwayLines(String version) { return List.of(); }
            @Override public TransitAliasSnapshot loadAliases(String version) { return aliases; }
        }
        MutableAliasProvider provider = new MutableAliasProvider();
        MasterDataTransitMappingResolver resolver = new MasterDataTransitMappingResolver(
                provider, null, new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(), 150.0);
        SegmentResolveRequest request = new SegmentResolveRequest(
                TransportMode.BUS, "카카오100", "출발", "도착", List.of("출발", "도착"),
                List.of(new VehicleInfo("카카오100", "BUS")),
                List.of(new Coordinate(127.0, 37.0), new Coordinate(127.01, 37.01)));

        TransitMappingResult beforeReview = resolver.resolve(request);
        provider.aliases = new TransitAliasSnapshot(
                java.util.Map.of("카카오100", Set.of("R1")),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        resolver.reloadWhenActiveVersionChanges();
        TransitMappingResult afterReview = resolver.resolve(request);

        assertThat(beforeReview.status()).isEqualTo(TransitMappingStatus.UNMAPPED);
        assertThat(afterReview.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(afterReview.officialRouteId()).isEqualTo("R1");
        assertThat(afterReview.mappingPolicyVersion())
                .isNotEqualTo(beforeReview.mappingPolicyVersion());
    }

    @Test
    void resolvesBusAndSubwayStepsIntoEveryOfficialAdjacentSection() {
        // 실제 저장소 구현 대신 손계산 가능한 작은 공식 마스터를 주입해 통합 경계를 검증한다.
        MasterDataTransitMappingResolver resolver = new MasterDataTransitMappingResolver(
                List.of(new BusRouteMaster("BUS-3412", "3412", List.of(
                        new BusStopMaster("B1", "잠실역", 10, 127.0, 37.0),
                        new BusStopMaster("B2", "삼성역", 11, 127.1, 37.1),
                        new BusStopMaster("B3", "강남역", 12, 127.2, 37.2)
                ))),
                List.of(new SubwayLineMaster("1002", "2호선", "INNER", List.of(
                        new SubwayStationMaster("S1", "강남", 1),
                        new SubwayStationMaster("S2", "선릉", 2),
                        new SubwayStationMaster("S3", "잠실", 3)
                )))
        );

        TransitMappingResult bus = resolver.resolve(new SegmentResolveRequest(
                TransportMode.BUS,
                "3412",
                "잠실역",
                "강남역",
                List.of("잠실역", "삼성역", "강남역"),
                List.of(new VehicleInfo("3412", "BUS")),
                List.of(new Coordinate(127.0, 37.0), new Coordinate(127.2, 37.2))
        ));
        TransitMappingResult subway = resolver.resolve(new SegmentResolveRequest(
                TransportMode.SUBWAY,
                "2호선",
                "강남역",
                "잠실역",
                List.of("강남역", "선릉역", "잠실역"),
                List.of(new VehicleInfo("2호선", "SUBWAY")),
                List.of()
        ));

        assertThat(bus.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(bus.officialRouteId()).isEqualTo("BUS-3412");
        assertThat(bus.segments()).extracting(segment -> segment.fromNodeId() + "->" + segment.toNodeId())
                .containsExactly("B1->B2", "B2->B3");
        assertThat(bus.segments()).extracting(segment -> segment.segmentId())
                .containsExactly("BUS:BUS-3412:B1:B2", "BUS:BUS-3412:B2:B3");
        assertThat(bus.realtimeCoverage()).isEqualTo(RealtimeMappingCoverage.FULL);

        assertThat(subway.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(subway.officialRouteId()).isEqualTo("1002");
        assertThat(subway.direction()).isEqualTo("INNER");
        assertThat(subway.segments()).extracting(segment -> segment.fromNodeId() + "->" + segment.toNodeId())
                .containsExactly("S1->S2", "S2->S3");
        assertThat(subway.segments()).extracting(segment -> segment.segmentId())
                .containsExactly("SUB:1002:S1:S2", "SUB:1002:S2:S3");
        assertThat(subway.realtimeCoverage()).isEqualTo(RealtimeMappingCoverage.FULL);
    }

    @Test
    void keepsAmbiguousResultWithoutIssuingAnyAdjacentSection() {
        MasterDataTransitMappingResolver resolver = new MasterDataTransitMappingResolver(
                List.of(
                        route("R1", "100", "A1", "A2"),
                        route("R2", "100", "B1", "B2")
                ),
                List.of()
        );

        TransitMappingResult result = resolver.resolve(new SegmentResolveRequest(
                TransportMode.BUS,
                "100",
                "출발",
                "도착",
                List.of("출발", "도착"),
                List.of(new VehicleInfo("100", "BUS")),
                List.of()
        ));

        assertThat(result.status()).isEqualTo(TransitMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("ROUTE_NAME_AMBIGUOUS");
        assertThat(result.officialRouteId()).isNull();
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void separatesExactSubwayTopologyFromMissingRealtimeCrosswalk() {
        MasterDataTransitMappingResolver resolver = new MasterDataTransitMappingResolver(
                List.of(),
                List.of(new SubwayLineMaster(
                        "2", null, "2호선", "UP", "MAIN", List.of(
                        new SubwayStationMaster("0222", null, "강남", 1),
                        new SubwayStationMaster("0223", null, "역삼", 2))))
        );

        TransitMappingResult result = resolver.resolve(new SegmentResolveRequest(
                TransportMode.SUBWAY, "2호선", "강남역", "역삼역",
                List.of("강남역", "역삼역"),
                List.of(new VehicleInfo("2호선", "SUBWAY")), List.of()));

        assertThat(result.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(result.segments()).extracting(segment -> segment.segmentId())
                .containsExactly("SUB:2:0222:0223");
        assertThat(result.realtimeCoverage()).isEqualTo(RealtimeMappingCoverage.NONE);
        assertThat(result.segments().get(0).realtimeLookupReady()).isFalse();
    }

    private BusRouteMaster route(String routeId, String routeName, String startId, String endId) {
        return new BusRouteMaster(routeId, routeName, List.of(
                new BusStopMaster(startId, "출발", 1, 127.0, 37.0),
                new BusStopMaster(endId, "도착", 2, 127.01, 37.01)
        ));
    }

    /** DB 없이 캐시 hit/miss와 버전 키 사용을 검증하는 작은 테스트 저장소다. */
    private static final class InMemoryMappingStore implements ProviderSegmentMappingStore {
        private TransitMappingResult value;
        private String savedSignature;
        private String savedMappingVersion;
        private String savedPolicyVersion;
        private boolean rejected;
        private int findCount;
        private int saveCount;

        @Override
        public ProviderSegmentMappingLookup find(
                String provider,
                TransportMode mode,
                String signature,
                String mappingVersion,
            String policyVersion) {
            findCount++;
            if (rejected) {
                return ProviderSegmentMappingLookup.rejected();
            }
            if (!java.util.Objects.equals(signature, savedSignature)
                    || !java.util.Objects.equals(mappingVersion, savedMappingVersion)
                    || !java.util.Objects.equals(policyVersion, savedPolicyVersion)) {
                return ProviderSegmentMappingLookup.miss();
            }
            return value == null || value.status() != TransitMappingStatus.EXACT
                    ? ProviderSegmentMappingLookup.miss()
                    : ProviderSegmentMappingLookup.exact(value);
        }

        @Override
        public void save(
                String provider,
                String signature,
                String mappingVersion,
                String policyVersion,
                SegmentResolveRequest request,
                TransitMappingResult result
        ) {
            saveCount++;
            savedSignature = signature;
            savedMappingVersion = mappingVersion;
            savedPolicyVersion = policyVersion;
            value = result;
        }
    }
}
