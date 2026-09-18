package com.ssafy.ddubi.domain.pipeline.controller;

import com.ssafy.ddubi.domain.route.mapping.ResourceSubwayMasterDataProvider;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 기존 INTERNAL API 키 인증 아래 Spark가 Backend와 동일한 gzip/버전/SHA를 받는다.
 * 설정된 시작 시점 스냅샷만 내보내며 요청 경로로 파일을 열거나 DB 마스터를 임의 변환하지 않는다.
 * 리소스를 설정하지 않은 배포에서는 404로 반환하여 빈 파일을 활성 마스터로 쓰지 못하게 한다. */
@RestController
@RequestMapping("/internal/transit")
public class InternalSubwayMasterController {
    private final ResourceSubwayMasterDataProvider provider;
    public InternalSubwayMasterController(ResourceSubwayMasterDataProvider provider) { this.provider=provider; }
    @GetMapping(value="/subway-master",produces="application/gzip")
    public ResponseEntity<byte[]> download() throws java.security.NoSuchAlgorithmException {
        var artifact=provider.artifact();
        if (artifact.isEmpty()) return ResponseEntity.notFound().build();
        byte[] bytes=artifact.get();
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return ResponseEntity.ok().header("X-Master-Version",provider.activeVersion().orElseThrow())
                .header("X-Content-SHA256",sha).eTag(sha).body(bytes);
    }
}
