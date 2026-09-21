package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공식 시간표를 운행일별 절대시각으로 정규화한 artifact를 시작 시 한 번 읽는다.
 * DB에 쓰지 않으며 열차번호와 실시간 ID의 대응은 수집팀이 검증한 버전만 받는다.
 * 자정 이후 운행을 다음 날짜로 명시하여 휴일·막차를 임의 배차 간격으로 복원하지 않는다.
 * 미설정/손상은 명시적 unavailable이다. 테스트 시간표를 운영 기본값으로 사용하지 않는다.
 */
@Component
public class SubwayTimetable {
    /**
     * static stationId와 실시간 providerStationId는 별개다. 통과역도 넣되 stops=false로 표시한다.
     * 공식 원본의 첫 역 도착/마지막 역 출발 결측만 null로 보존한다. 정차시간 0초로 채우지 않는다.
     */
    public record Call(String stationId, String providerStationId, Instant arrivalAt, Instant departureAt, boolean stops) { }
    /**
     * 한 운행일의 단방향 운행이다. trainId는 해당 제공자의 운행 열차 ID이며 편성 고유번호가 아니다.
     * direction은 canonical 방향, arrivalDirection/positionDirection은 각 API 원문 코드다.
     * arrivalDestination/positionDestination은 별도 검증한 원문 행선지다. 이름만으로 ID를 생성하지 않는다.
     * 실시간 열차 대응이 없으면 trainId와 다섯 원문 식별 필드를 모두 null로 둔다.
     * 이 경우 canonical 경로와 역 대응만으로 시간표 계산을 하며 Redis 열차 키를 만들지 않는다.
     */
    public record Trip(String key, String lineId, String providerLineId, String direction, String trainId,
                       String arrivalDirection, String positionDirection, String arrivalDestination,
                       String positionDestination, String trainType, LocalDate serviceDate, List<Call> calls) {
        public Trip { calls = List.copyOf(calls); }
        public boolean realtimeVerified() { return trainId != null; }
    }
    /** publishedAt은 확보 시각이다. validFrom/Until은 운행 적용 범위로 끝 시각을 포함하지 않는다. */
    public record Data(String version, String masterDataVersion, String source, Instant publishedAt,
                       Instant validFrom, Instant validUntil, List<Trip> trips, List<String> limitations) {
        public Data { trips = List.copyOf(trips); limitations = limitations == null ? List.of() : List.copyOf(limitations); }
        /** 기존 artifact/합성 fixture와 호환한다. 신규 원천의 커버리지 제약은 limitations에 명시한다. */
        public Data(String version, String masterDataVersion, String source, Instant publishedAt,
                    Instant validFrom, Instant validUntil, List<Trip> trips) {
            this(version, masterDataVersion, source, publishedAt, validFrom, validUntil, trips, List.of());
        }
    }
    private final Data data;
    private final String unavailableReason;
    private final Map<List<String>,List<Trip>> index;

    /** 경로는 서버 설정 전용이다. 32 MiB 초과 파일과 불완전한 운행표는 전체 거절하여 부분 적재를 숨기지 않는다. */
    @org.springframework.beans.factory.annotation.Autowired
    public SubwayTimetable(ObjectMapper json, @Value("${ddubi.subway-timing.timetable-path:}") String path) {
        Data loaded = null;
        String reason = "SUBWAY_TIMETABLE_NOT_CONFIGURED";
        if (path != null && !path.isBlank()) {
            try {
                Path file = Path.of(path);
                if (Files.size(file) > 32L * 1024 * 1024) throw new IllegalArgumentException();
                // JSON 전체 문자열을 별도로 만들지 않고 파싱한다. 실패하더라도 파일 핸들은 반환한다.
                try (var input = Files.newInputStream(file)) {
                    loaded = validate(json.readValue(input, Data.class));
                }
                reason = null;
            } catch (Exception invalid) { loaded = null; reason = "SUBWAY_TIMETABLE_INVALID"; }
        }
        data = loaded; unavailableReason = reason; index=buildIndex(loaded);
    }

    /** 고정 증거 Replay와 테스트용 입력도 운영과 같은 검증을 거친다. */
    SubwayTimetable(Data data) { this.data = validate(data); this.unavailableReason = null; index=buildIndex(this.data); }
    public Data data() { return data; }
    public String unavailableReason() { return unavailableReason; }

    /** canonical 노선·방향·승차역으로 후보를 좁힌다. 회차와 실제 연속 구간 검증은 소비자가 수행한다. */
    public List<Trip> tripsFor(String line,String direction,String station) {
        if(line==null || direction==null || station==null) return List.of();
        return index.getOrDefault(List.of(line,direction,station),List.of());
    }
    private static Map<List<String>,List<Trip>> buildIndex(Data data) {
        var grouped=new HashMap<List<String>,List<Trip>>();
        if(data!=null) for(var trip:data.trips()) {
            var seen=new HashSet<String>();
            for(var call:trip.calls()) if(seen.add(call.stationId()))
                grouped.computeIfAbsent(List.of(trip.lineId(),trip.direction(),call.stationId()),k->new ArrayList<>()).add(trip);
        }
        grouped.replaceAll((k,v)->List.copyOf(v));
        return Map.copyOf(grouped);
    }

    private static Data validate(Data d) {
        if (d == null || blank(d.version()) || blank(d.masterDataVersion()) || blank(d.source())
                || d.publishedAt() == null || d.validFrom() == null || d.validUntil() == null
                || !d.validFrom().isBefore(d.validUntil()) || d.trips().isEmpty()) throw new IllegalArgumentException();
        var keys = new HashSet<String>();
        var identities = new HashSet<String>();
        for (Trip t : d.trips()) {
            if (blank(t.key()) || !keys.add(t.key()) || blank(t.lineId())
                    || (t.providerLineId() != null && !id(t.providerLineId()))
                    || blank(t.direction()) || t.serviceDate() == null || t.calls().size() < 2) throw new IllegalArgumentException();
            // 정적 TRAIN_NO를 실시간 trainId로 복사하는 것을 피한다. 부분 대응 역시 승인하지 않는다.
            if (t.realtimeVerified()) {
                if (!id(t.providerLineId()) || !id(t.trainId()) || blank(t.arrivalDirection()) || blank(t.positionDirection())
                        || blank(t.arrivalDestination()) || blank(t.positionDestination()) || blank(t.trainType())
                        || !identities.add(t.serviceDate() + ":" + t.providerLineId() + ":" + t.trainId())) throw new IllegalArgumentException();
            } else if (t.arrivalDirection() != null || t.positionDirection() != null || t.arrivalDestination() != null
                    || t.positionDestination() != null || t.trainType() != null) throw new IllegalArgumentException();
            var stations = new HashSet<String>();
            var providerStations = new HashSet<String>();
            Instant previous = null;
            Instant day = t.serviceDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            for (int i = 0; i < t.calls().size(); i++) {
                Call c = t.calls().get(i);
                Instant arrival = c.arrivalAt(), departure = c.departureAt();
                // 첫/끝의 없는 시각은 검증 경계에서만 반대편 시각을 사용한다. 원본 null은 그대로 반환한다.
                if (arrival == null && i == 0) arrival = departure;
                if (departure == null && i == t.calls().size() - 1) departure = arrival;
                // 정적 시간표는 절대시각으로 회차를 구별한다. 실시간 위치만으로 회차를 판별할 수 없는 반복 운행은 거절한다.
                if (blank(c.stationId()) || (t.realtimeVerified() && (!stations.add(c.stationId()) || !id(c.providerStationId())
                        || !providerStations.add(c.providerStationId())))
                        || (c.providerStationId() != null && !id(c.providerStationId())) || arrival == null || departure == null
                        || departure.isBefore(arrival) || (previous != null && !arrival.isAfter(previous))
                        || arrival.isBefore(d.validFrom()) || !departure.isBefore(d.validUntil())
                        || arrival.isBefore(day) || !departure.isBefore(day.plus(Duration.ofHours(30)))) throw new IllegalArgumentException();
                previous = departure;
            }
        }
        return d;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean id(String s) { return s != null && s.matches("[0-9A-Za-z_-]{1,60}"); }
}
