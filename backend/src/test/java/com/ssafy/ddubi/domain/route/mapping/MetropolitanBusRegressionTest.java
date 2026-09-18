package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static com.ssafy.ddubi.domain.route.mapping.MetropolitanBusFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** 기존 버스 매핑이 방어하던 오탐을 수도권 알고리즘으로 재검증한다.
 * 예전 전역 별칭·150m 정책은 승계하지 않는다. 수동 대응은 별도 검토일/입력 해시 테스트가 담당한다. */
class MetropolitanBusRegressionTest {
    BusMappingRequest req(List<String> vehicles,List<String> names,List<Coordinate> points) {
        return new BusMappingRequest(vehicles,"출발","도착",names,points);
    }
    List<BusAlternativeMapping> match(List<MetropolitanBusMaster.Route> routes,BusMappingRequest request) {
        return new MetropolitanBusMatcher(master(routes)).match(request,LocalDate.of(2026,9,10));
    }
    @Test void preservesEveryIntermediateOfficialIdAndSequence() {
        var result=match(List.of(regular()),req(List.of("100번"),List.of("출발","중간","도착"),geometry())).get(0);
        assertThat(result.geometryStatus()).isEqualTo("VERIFIED");
        assertThat(result.candidates().get(0).stops()).extracting(MetropolitanBusMaster.Stop::stopId).containsExactly("A","B","C");
        assertThat(result.candidates().get(0).stops()).extracting(MetropolitanBusMaster.Stop::sequence).containsExactly(1,2,3);
    }
    static Stream<List<Coordinate>> invalidPaths() {
        var reverse=new ArrayList<>(geometry());Collections.reverse(reverse);
        return Stream.of(List.of(),reverse,List.of(new Coordinate(128,38),new Coordinate(128.004,38)),
                List.of(new Coordinate(127.002,37.5),new Coordinate(127.002,37.5)));
    }
    @ParameterizedTest @MethodSource("invalidPaths")
    void missingFarReversedOrZeroLengthGeometryNeverVerifies(List<Coordinate> points) {
        assertThat(match(List.of(regular()),req(List.of("100"),List.of("출발","중간","도착"),points)).get(0).geometryStatus()).isNotEqualTo("VERIFIED");
    }
    @Test void rejectsReverseEvenWhenBothEndpointsAreWithinFiftyMeters() {
        var route=route("R1","100",stop("A","출발",1,127,37.5),stop("B","도착",2,127.0004,37.5));
        var result=match(List.of(route),req(List.of("100"),List.of("출발","도착"),
                List.of(new Coordinate(127.0004,37.5),new Coordinate(127,37.5)))).get(0);
        assertThat(result.geometryStatus()).isEqualTo("CONFLICT");
        assertThat(result.reason()).isEqualTo("DIRECTION_NOT_CONFIRMED");
    }
    @Test void preservesTwoVehiclesAsIndependentAlternatives() {
        var second=new MetropolitanBusMaster.Route("SEOUL_BUS","R2","200","서울",regular().stops(),Map.of());
        var result=match(List.of(regular(),second),req(List.of("100","200"),List.of("출발","중간","도착"),geometry()));
        assertThat(result).extracting(BusAlternativeMapping::providerName).containsExactly("100","200");
        assertThat(result).allSatisfy(r->assertThat(r.geometryStatus()).isEqualTo("VERIFIED"));
    }
    @Test void missingVehicleRemainsVisibleAlongsideVerifiedVehicle() {
        assertThat(match(List.of(regular()),req(List.of("100","200"),List.of("출발","중간","도착"),geometry())))
                .extracting(BusAlternativeMapping::identityStatus).containsExactly("CONFIRMED","UNRESOLVED");
    }
    @Test void sameNumberAndLocationWithDifferentOfficialIdsRemainsAmbiguous() {
        var second=new MetropolitanBusMaster.Route("SEOUL_BUS","R2","100","서울",regular().stops(),Map.of());
        var result=match(List.of(regular(),second),req(List.of("100"),List.of("출발","중간","도착"),geometry())).get(0);
        assertThat(result.identityStatus()).isEqualTo("AMBIGUOUS");assertThat(result.candidates()).hasSize(2);
    }
    @Test void conflictingBranchesUnderOneSourceIdAreRejectedAtMasterBoundary() {
        var branch=route("R1","100",stop("A","출발",1,127,37.5),stop("X","분기",2,127.002,37.5),stop("C","도착",3,127.004,37.5));
        assertThatThrownBy(()->new MetropolitanBusMatcher(master(List.of(regular(),branch))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("노선 중복");
    }
    @Test void rejectsKnownIntermediateNamesInReverseOrder() {
        var route=route("R1","100",stop("A","출발",1,127,37.5),stop("B","중간A",2,127.001,37.5),
                stop("C","중간B",3,127.003,37.5),stop("D","도착",4,127.004,37.5));
        assertThat(match(List.of(route),req(List.of("100"),List.of("출발","중간B","중간A","도착"),geometry())).get(0).geometryStatus()).isNotEqualTo("VERIFIED");
    }
}
