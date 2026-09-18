package com.ssafy.ddubi.domain.pipeline.dto.response;

/**
 * 업로드 결과.
 *
 * @param dataVersion 올린 버전
 * @param received    이번 요청에 담겨 온 행 수
 * @param versionTotal 이 버전에 지금까지 쌓인 총 행 수. Spark 가 청크를 다 보냈는지
 *                     스스로 대조할 수 있게 돌려준다
 */
public record BaselineUploadResponse(
        String dataVersion,
        int received,
        long versionTotal
) {
}
