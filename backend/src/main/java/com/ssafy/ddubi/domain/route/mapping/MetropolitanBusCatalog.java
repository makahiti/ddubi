package com.ssafy.ddubi.domain.route.mapping;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
import java.io.IOException;

/** 수도권 버스 메모리 스냅샷을 소유한다. 원본 파일을 요청마다 읽지 않는다.
 * 새 버전의 인덱스 완성 후 참조를 교체하고 재적재 실패에는 마지막 정상 버전을 유지한다.
 * 항상 활성 마스터를 읽으며 미적재는 null 스냅샷으로 나타낸다. 다른 버스 매핑기로 우회하지 않는다. */
@Component
@DependsOnDatabaseInitialization
public class MetropolitanBusCatalog {
    /** 검증된 artifact 버전과 완성된 불변 Matcher 인덱스다. 요청 중 교체되어도 기존 참조는 유효하다. */
    public record Snapshot(String version,MetropolitanBusMatcher matcher) { }
    private final MetropolitanBusMasterService service;
    private volatile Snapshot snapshot;
    /** 일반 기동은 활성 DB 버전을 읽기만 한다. 관리 적재는 별도 Runner가 담당한다. */
    @Autowired
    public MetropolitanBusCatalog(MetropolitanBusMasterService service) throws IOException {
        this.service=service;
        reload();
    }
    /** 오프라인 Replay용 고정 스냅샷이다. DB 접근·주기 갱신 없이 같은 Matcher를 사용한다. */
    public MetropolitanBusCatalog(MetropolitanBusMaster master) {
        this.service=null;
        snapshot=new Snapshot(master.version(),new MetropolitanBusMatcher(master));
    }
    /** 한 요청은 이 불변 참조를 고정하여 버전 교체 중에도 같은 인덱스를 사용한다. */
    public Snapshot snapshot(){return snapshot;}
    /** 전체 인덱스를 완성한 뒤 교체한다. 적재 예외는 기존 스냅샷을 훼손하지 않는다. */
    public synchronized void reload() throws IOException {
        if(service==null)return;
        String version=service.activeVersion().orElse(null);
        if(version==null){snapshot=null;return;}
        if(snapshot!=null && snapshot.version().equals(version))return;
        var master=service.load(version);
        snapshot=new Snapshot(version,new MetropolitanBusMatcher(master));
    }
    /** 다른 인스턴스의 활성화 결과를 주기적으로 읽고 실패하면 마지막 정상 버전을 유지한다. */
    @Scheduled(fixedDelayString="${ddubi.transit-mapping.refresh-interval-ms:60000}")
    public void refresh() {
        if(service==null)return;
        try {reload();}
        catch(Exception e){LoggerFactory.getLogger(getClass()).warn("수도권 마스터 갱신 실패: 기존 버전 유지",e);}
    }
}
