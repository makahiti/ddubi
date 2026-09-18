package com.ssafy.ddubi.domain.pipeline.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 활성화 결과.
 *
 * @param activeVersion   이제 서빙되는 버전
 * @param previousVersion 직전 버전. 롤백 대상이자 삭제 보호 대상이다. 첫 활성화면 null
 * @param rowCount        활성 버전의 행 수
 * @param activatedAt     활성화 시각. 기준선이 오래됐는지 판단하는 기준이 된다
 * @param purgedVersions  이번에 함께 지운 고아 버전들. 아무것도 안 지웠으면 빈 배열
 */
public record BaselineActivateResponse(
        String activeVersion,
        String previousVersion,
        long rowCount,
        LocalDateTime activatedAt,
        List<String> purgedVersions
) {
}
