package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 버전·방향·발표시각·통과시각을 검증하고 사건 미수집을 무사고로 오인하지 않는다. */
class RoadImpactEvidenceTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    final Instant now = Instant.parse("2026-09-10T10:00:00Z");
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
    RoadImpactEvidence service(boolean direction) throws Exception {
        var row=mapper.createObjectNode();
        row.put("segmentId","BUS:1:1:2"); row.put("namespace","SEOUL_BUS"); row.put("routeId","1");
        row.put("fromStopId","1"); row.put("toStopId","2"); row.put("fromOrder",1); row.put("toOrder",2);
        row.put("direction","FORWARD"); row.put("coverage","COMPLETE"); row.put("directionVerified",direction);
        row.put("evidenceMethod","OFFICIAL_ROUTE_LINKS"); row.put("evidenceRef","fixture");
        row.put("validFrom","2026-09-10T00:00:00Z"); row.put("validUntil","2026-09-11T00:00:00Z");
        row.putArray("roadLinks").addObject().put("namespace","SEOUL_ACCINFO_LINK").put("linkId","1");
        var root=mapper.createObjectNode().put("schemaVersion",1).put("version","road1").put("masterVersion","master").put("busCrosswalkSha256","a".repeat(64));
        root.putArray("mappings").add(row);
        byte[] bytes=mapper.writeValueAsBytes(root); Path file=dir.resolve("road.json"); Files.write(file,bytes);
        when(redis.opsForHash()).thenReturn(hashes);
        return new RoadImpactEvidence(mapper,redis,file.toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),600);
    }
    @Test void passageIntervalAndExpectedClearAreNotActualClosure() throws Exception {
        when(hashes.entries(anyString())).thenReturn(Map.of("i", "{\"eventId\":\"e\",\"linkNamespace\":\"SEOUL_ACCINFO_LINK\",\"linkId\":\"1\",\"collectedAt\":\"2026-09-10T10:00:00Z\",\"startsAt\":\"2026-09-10T09:00:00Z\",\"expectedEndsAt\":\"2026-09-10T10:10:00Z\"}"));
        var route=MlRoutePreviewTest.route(0,"1",60,0,1);
        var snapshot=service(true).read(List.of(route),now);
        assertThat(RoadImpactEvidence.reasons(route.segments().get(0),snapshot,now,now.plusSeconds(60))).contains("ROAD_POTENTIAL_IMPACT:e");
        assertThat(RoadImpactEvidence.reasons(route.segments().get(0),snapshot,now.plusSeconds(700),now.plusSeconds(760))).contains("ROAD_EXPECTED_CLEAR_UNCONFIRMED").noneMatch(r->r.startsWith("ROAD_POTENTIAL_IMPACT"));
    }
    @Test void missingFeedAndUnreviewedDirectionFailConservatively() throws Exception {
        when(hashes.entries(anyString())).thenReturn(Map.of());
        var route=MlRoutePreviewTest.route(0,"1",60,0,1);
        assertThat(RoadImpactEvidence.reasons(route.segments().get(0),service(true).read(List.of(route),now),now,now.plusSeconds(60))).contains("ROAD_FEED_COVERAGE_UNKNOWN");
        assertThatThrownBy(()->service(false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void alternativeKeepsFastestAndRejectsUnknownEvidenceAndRoundTrips() throws Exception {
        var fastest=new RouteTimelineEvaluator.Result("fast",now,now.plusSeconds(30),RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE,true,List.of(),List.of("ROAD_POTENTIAL_IMPACT:e"),"p");
        var alternative=new RouteTimelineEvaluator.Result("alt",now,now.plusSeconds(60),RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE,true,List.of(),List.of(),"p");
        var comparison=new MlRoutePreview.Comparison("PREVIEW",false,fastest,List.of(new MlRoutePreview.Candidate(fastest,"API_ONLY",0,1,0,0),new MlRoutePreview.Candidate(alternative,"API_ONLY",0,1,0,0)),List.of(),false,List.of(),List.of());
        assertThat(comparison.fastest().journeyKey()).isEqualTo("fast");
        assertThat(comparison.roadAlternative().journeyKey()).isEqualTo("alt");
        var json=new ObjectMapper().findAndRegisterModules();
        assertThat(json.readValue(json.writeValueAsString(comparison),MlRoutePreview.Comparison.class).roadAlternative().journeyKey()).isEqualTo("alt");
        var unknown=new RouteTimelineEvaluator.Result("alt",now,now.plusSeconds(60),RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE,true,List.of(),List.of("ROAD_IMPACT_UNKNOWN"),"p");
        assertThat(new MlRoutePreview.Comparison("PREVIEW",false,fastest,List.of(new MlRoutePreview.Candidate(unknown,"API_ONLY",0,1,0,0)),List.of(),false,List.of(),List.of()).roadAlternative()).isNull();
    }
}
