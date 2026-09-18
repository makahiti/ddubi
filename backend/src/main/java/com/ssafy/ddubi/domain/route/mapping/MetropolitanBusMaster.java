package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** 수도권 CSV에서 만든 불변 서빙 artifact. 출처 키는 공용 segment_id와 구분한다.
 * 원본 순번의 구멍은 유지하여 해당 구간만 매핑에서 거절한다. 잘못된 ID·좌표·중복 순번은
 * 적재 전체를 실패시키며 마지막 정상 활성 버전에는 영향을 주지 않는다. */
public record MetropolitanBusMaster(int schemaVersion, String version, String sourceCsvSha256,
        String sourceMetadataSha256, List<Route> routes, List<Review> reviews) {
    public MetropolitanBusMaster {
        routes = routes == null ? List.of() : List.copyOf(routes);
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
    }
    /** 노선별 공식 정류장열. namespace는 ID 발급/자료 출처이며 region은 관할 근거다. */
    public record Route(String namespace, String routeId, String routeName, String region,
            List<Stop> stops, Map<String,List<Coordinate>> shapes) {
        public Route {
            stops = List.copyOf(stops);
            Map<String,List<Coordinate>> safe = new TreeMap<>();
            if (shapes != null) shapes.forEach((k,v) -> safe.put(k,List.copyOf(v)));
            shapes = Map.copyOf(safe);
        }
        public String key() { return namespace + "." + routeId; }
    }
    /** 같은 stop ID의 반복 방문도 순번별 보존한다. boardingAllowed=null은 승차 가능 확인이 아니다. */
    public record Stop(String namespace, String stopId, String stopName, int sequence,
            String direction, Boolean boardingAllowed, Double longitude, Double latitude,
            String mobileCode, String coordinateStatus) {
        public Coordinate point() { return longitude == null || latitude == null ? null : new Coordinate(longitude,latitude); }
    }
    /** 사람이 확인한 특정 입력의 선택 노선. 적용일과 전체 입력 SHA를 엄격히 고정한다. */
    public record Review(String observedDate, String providerName, String requestSha256,
            String routeKey, List<String> stopIds, List<String> mobileCodes) {
        public Review {
            stopIds=stopIds==null?List.of():List.copyOf(stopIds);
            mobileCodes=mobileCodes==null?List.of():List.copyOf(mobileCodes);
        }
    }

    /** 파일 전체를 검증 후 반환한다. 실행 요청에서는 호출하지 않고 적재/Replay에만 사용한다. */
    public static MetropolitanBusMaster read(Path path) throws IOException {
        return read(Files.readAllBytes(path));
    }
    public static MetropolitanBusMaster read(byte[] gzip) throws IOException {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            byte[] json = input.readNBytes(160_000_001);
            if (json.length > 160_000_000) throw new IOException("마스터 크기 제한 초과");
            var result = new ObjectMapper().readValue(json, MetropolitanBusMaster.class);
            result.validate();
            return result;
        }
    }
    /** 출처별 복합키 중복과 잘못된 좌표를 적재 전에 차단한다. 결측 좌표는 null로 남긴다. */
    public void validate() {
        if (schemaVersion != 1 || version == null || !version.matches("[A-Za-z0-9._-]{1,50}") || routes.isEmpty())
            throw new IllegalArgumentException("마스터 버전/스키마/노선 누락");
        if (sourceCsvSha256 == null || !sourceCsvSha256.matches("[a-f0-9]{64}")
                || sourceMetadataSha256 == null || !sourceMetadataSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("출처 해시 누락");
        Set<String> keys = new HashSet<>();
        for (Route r : routes) {
            id(r.namespace()); id(r.routeId());
            if (!keys.add(r.key()) || r.routeName() == null || r.routeName().isBlank() || r.stops().size()<2)
                throw new IllegalArgumentException("노선 중복/필수값 오류: " + r.key());
            int previous = 0;
            for (Stop s : r.stops()) {
                id(s.namespace()); id(s.stopId());
                if (s.sequence() <= previous || s.stopName()==null || s.stopName().isBlank()
                        || (s.longitude()==null) != (s.latitude()==null)
                        || (s.point()!=null && !BusTrace.valid(s.point())))
                    throw new IllegalArgumentException("정류장 순번/좌표 오류: " + r.key() + "/" + s.sequence());
                previous=s.sequence();
            }
            for (var shape:r.shapes().values()) if(shape.size()<2 || shape.stream().anyMatch(p->!BusTrace.valid(p)))
                throw new IllegalArgumentException("공식 형상 오류: " + r.key());
        }
        for (Review r:reviews) {
            java.time.LocalDate.parse(r.observedDate());
            if (r.providerName()==null || r.providerName().isBlank() || !keys.contains(r.routeKey())
                    || r.requestSha256()==null || !r.requestSha256().matches("[a-f0-9]{64}")
                    || (!r.mobileCodes().isEmpty() && r.mobileCodes().size()!=r.stopIds().size()))
                throw new IllegalArgumentException("검토 대응표 오류");
        }
    }
    private static void id(String v) {
        if(v==null || !v.matches("[A-Za-z0-9._-]{1,50}")) throw new IllegalArgumentException("공식 ID 형식 오류");
    }
    public static String sha(byte[] value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
