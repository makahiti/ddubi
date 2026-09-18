package com.ssafy.ddubi.domain.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 날씨/백분위를 꾸미지 않는 불변 청크 저장소. 조회는 검토용이며 추천 계산에 적용하지 않는다. */
@Service
public class BusReferenceService {
    public record Row(String masterVersion, String namespace, String routeId, String fromStopId,
            String toStopId, int fromOrder, int toOrder, int dow, int hourSlot, String sampleKind,
            double meanSeconds, long sampleCount, int observedDates, LocalDate observationStart,
            LocalDate observationEnd, String sourceSha256) {}
    public record Upload(List<Row> rows) {}
    public record Query(String masterVersion, String namespace, String routeId, String fromStopId,
            String toStopId, int fromOrder, int toOrder, int dow, int hourSlot) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public BusReferenceService(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }

    static void require(boolean condition) {
        if(!condition) throw new BusinessException(ErrorCode.INVALID_REQUEST, "참고자료 계약이 올바르지 않습니다.");
    }
    static void token(String value, int max) {
        require(value!=null && value.length()<=max && value.matches("[A-Za-z0-9._-]+"));
    }
    public static void validate(Row row) {
        require(row!=null);
        validate(new Query(row.masterVersion(),row.namespace(),row.routeId(),row.fromStopId(),
                row.toStopId(),row.fromOrder(),row.toOrder(),row.dow(),row.hourSlot()));
        require(Set.of("HOURLY_SOURCE_MEAN", "ACTUAL_TRAVERSAL_MEAN").contains(Objects.toString(row.sampleKind(),"")));
        require(Double.isFinite(row.meanSeconds()) && row.meanSeconds()>0 && row.meanSeconds()<=86400);
        require(row.sampleCount()>0 && row.observedDates()>0 && row.sampleCount()>=row.observedDates());
        require(row.observationStart()!=null && row.observationEnd()!=null
                && !row.observationEnd().isBefore(row.observationStart()));
        require(row.observedDates()<=java.time.temporal.ChronoUnit.DAYS.between(row.observationStart(),row.observationEnd())+1);
        require(row.sourceSha256()!=null && row.sourceSha256().matches("[a-f0-9]{64}"));
    }
    public static void validate(Query q) {
        require(q!=null); token(q.masterVersion(),100);token(q.namespace(),30);token(q.routeId(),80);
        token(q.fromStopId(),80);token(q.toStopId(),80);
        require(q.fromOrder()>0 && (long)q.toOrder()==(long)q.fromOrder()+1);
        require(!q.fromStopId().equals(q.toStopId()) && q.dow()>=1 && q.dow()<=7 && q.hourSlot()>=0 && q.hourSlot()<=23);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw new BusinessException(ErrorCode.INVALID_REQUEST,"참고자료 직렬화 실패"); }
    }
    @Transactional
    public Map<String,Object> upload(String version, int chunk, Upload request) {
        token(version,80); require(chunk>=0 && request!=null && request.rows()!=null
                && !request.rows().isEmpty() && request.rows().size()<=1000);
        request.rows().forEach(BusReferenceService::validate);
        String body=encode(request.rows());
        String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
        // 충돌 청크도 동일 키 잠금으로 직렬화한다. INSERT IGNORE로 오류를 숨기지 않는다.
        db.update("INSERT INTO bus_reference_chunk(data_version,chunk_id,payload_sha256) VALUES(?,?,?) ON DUPLICATE KEY UPDATE chunk_id=chunk_id",
                version,chunk,digest);
        String stored=db.queryForObject("SELECT payload_sha256 FROM bus_reference_chunk WHERE data_version=? AND chunk_id=? FOR UPDATE",String.class,version,chunk);
        if(!digest.equals(stored)) throw new BusinessException(ErrorCode.INVALID_STATE,"같은 청크 번호에 다른 내용이 이미 저장돼 있습니다.");
        Integer count=db.queryForObject("SELECT count(*) FROM bus_reference_row WHERE data_version=? AND chunk_id=?",Integer.class,version,chunk);
        if(count!=null && count>0) {
            require(count==request.rows().size());
            return Map.of("receivedRows",count,"reused",true,"productionReady",false);
        }
        var args=new ArrayList<Object>();
        var tuples=new ArrayList<String>();
        for(int i=0;i<request.rows().size();i++) {
            var r=request.rows().get(i);
            Collections.addAll(args,version,chunk,i,r.masterVersion(),r.namespace(),r.routeId(),r.fromStopId(),
                    r.toStopId(),r.fromOrder(),r.toOrder(),r.dow(),r.hourSlot(),r.sampleKind(),encode(r));
            tuples.add("(?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        }
        // 청크 전체를 한 bound multi-values INSERT로 보낸다. 드라이버 전역 옵션에 의존하지 않는다.
        db.update("INSERT INTO bus_reference_row VALUES "+String.join(",",tuples),args.toArray());
        return Map.of("receivedRows",request.rows().size(),"reused",false,"productionReady",false);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> status(String version) {
        token(version,80);
        Long chunks=db.queryForObject("SELECT count(*) FROM bus_reference_chunk WHERE data_version=?",Long.class,version);
        Long rows=db.queryForObject("SELECT count(*) FROM bus_reference_row WHERE data_version=?",Long.class,version);
        return Map.of("dataVersion",version,"totalRows",rows==null?0L:rows,"totalChunks",chunks==null?0L:chunks,
                "productionReady",false,"realtimeApplied",false);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> lookup(String version, Query q) {
        token(version,80);validate(q);
        var rows=db.query("SELECT payload FROM bus_reference_row WHERE data_version=? AND master_version=? AND namespace=? AND route_id=? AND from_stop_id=? AND to_stop_id=? AND from_order=? AND to_order=? AND dow=? AND hour_slot=? ORDER BY chunk_id,row_index LIMIT 101",
                (rs,n)->{ try { return json.readValue(rs.getString(1),Row.class); } catch(Exception e) {throw new IllegalStateException("참고자료 손상",e);} },
                version,q.masterVersion(),q.namespace(),q.routeId(),q.fromStopId(),q.toStopId(),q.fromOrder(),q.toOrder(),q.dow(),q.hourSlot());
        boolean truncated=rows.size()>100;
        return Map.of("status",rows.isEmpty()?"REFERENCE_MISSING":"STAGED_REFERENCE_NOT_APPROVED",
                "productionReady",false,"realtimeApplied",false,"truncated",truncated,
                "rows",truncated?rows.subList(0,100):rows,
                "limitations",List.of("WEATHER_NOT_JOINED","SOURCE_AVAILABILITY_UNKNOWN","MASTER_EFFECTIVE_DATE_UNVERIFIED"));
    }
}
