package com.ssafy.ddubi.domain.route.mapping;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 검증된 수도권 artifact를 MySQL에 원자적으로 적재/활성화하는 Spring 관리 서비스.
 * 같은 버전·같은 해시는 멱등, 같은 버전·다른 내용은 거부한다. 실패 시 기존 활성 포인터가
 * 유지되며 서울 버스/지하철 테이블은 변경하지 않는다. 자동 네트워크 재시도는 하지 않는다. */
@Service
public class MetropolitanBusMasterService {
    private final JdbcTemplate jdbc;
    public MetropolitanBusMasterService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    /** 입력 전체를 먼저 검증하고 singleton 잠금 안에서 INSERT와 포인터 전환을 수행한다. */
    @Transactional(rollbackFor=Exception.class)
    public String importAndActivate(Path artifact,String expectedSha256) throws IOException {
        byte[] bytes=Files.readAllBytes(artifact);
        String sha=MetropolitanBusMaster.sha(bytes);
        if(expectedSha256==null || !sha.equals(expectedSha256))throw new IllegalArgumentException("artifact SHA-256 불일치");
        var master=MetropolitanBusMaster.read(bytes);
        jdbc.queryForList("SELECT singleton_id FROM metropolitan_bus_master_active WHERE singleton_id=1 FOR UPDATE");
        List<String> existing=jdbc.query("SELECT artifact_sha256 FROM metropolitan_bus_master WHERE data_version=?",
                (rs,n)->rs.getString(1),master.version());
        if(!existing.isEmpty() && !existing.get(0).equals(sha))throw new IllegalArgumentException("같은 버전의 다른 마스터는 허용하지 않습니다");
        if(existing.isEmpty())jdbc.update("INSERT INTO metropolitan_bus_master(data_version,artifact_sha256,artifact_gzip,route_count,stop_count) VALUES(?,?,?,?,?)",
                master.version(),sha,bytes,master.routes().size(),master.routes().stream().mapToInt(r->r.stops().size()).sum());
        int updated=jdbc.update("UPDATE metropolitan_bus_master_active SET data_version=?,activated_at=CURRENT_TIMESTAMP(6) WHERE singleton_id=1",master.version());
        if(updated!=1)throw new IllegalStateException("활성 포인터 행 누락");
        return master.version();
    }
    /** 작은 활성 포인터만 주기적으로 읽는다. artifact는 버전 변경 시에만 조회한다. */
    public Optional<String> activeVersion() {
        return jdbc.query("SELECT data_version FROM metropolitan_bus_master_active WHERE singleton_id=1 AND data_version IS NOT NULL",
                (rs,n)->rs.getString(1)).stream().findFirst();
    }
    public MetropolitanBusMaster load(String version) throws IOException {
        var rows=jdbc.query("SELECT artifact_sha256,artifact_gzip FROM metropolitan_bus_master WHERE data_version=?",
                (rs,n)->Map.entry(rs.getString(1),rs.getBytes(2)),version);
        if(rows.size()!=1)throw new IOException("마스터 버전 미확인");
        var row=rows.get(0);
        if(!MetropolitanBusMaster.sha(row.getValue()).equals(row.getKey()))throw new IOException("저장 마스터 해시 손상");
        var master=MetropolitanBusMaster.read(row.getValue());
        if(!version.equals(master.version()))throw new IOException("저장 마스터 버전 불일치");
        return master;
    }
}
