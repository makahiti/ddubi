package com.ssafy.ddubi.domain.route.mapping;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.Path;

/** 명시적인 관리 실행에서만 artifact를 적재한다. 원격 API/운영 파일 다운로드는 수행하지 않는다. */
@Component
@ConditionalOnProperty(prefix="ddubi.metropolitan-bus",name="import-enabled",havingValue="true")
public class MetropolitanBusImportRunner implements ApplicationRunner {
    private final MetropolitanBusMasterService service;
    private final MetropolitanBusCatalog catalog;
    private final String file,sha;
    public MetropolitanBusImportRunner(MetropolitanBusMasterService service,MetropolitanBusCatalog catalog,
            @Value("${ddubi.metropolitan-bus.artifact:}") String file,
            @Value("${ddubi.metropolitan-bus.sha256:}") String sha) {
        this.service=service;this.catalog=catalog;this.file=file;this.sha=sha;
    }
    public void run(ApplicationArguments args) throws Exception {
        if(file.isBlank() || sha.isBlank())throw new IllegalArgumentException("수도권 artifact 경로와 SHA-256 필요");
        service.importAndActivate(Path.of(file),sha);
        catalog.reload();
    }
}
