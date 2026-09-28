# 날씨 독립 버스 참고자료 저장·조회

## 후속 연결
전체 내보내기와 공개 참고조회가 구현되었다. 명시적 설정·전체 적재 검증을 거친 과거 평균 계산 fallback은
`bus-historical-fallback.md`, 서버 실행 절차는 `../transit-stage3-deployment.md`를 따른다.
아래 저장 API의 productionReady=false는 실시간/ML 승인 없음이라는 계약이며 과거 평균 사용 여부와 별개다.

## 운영 기준선과 분리
평균을 p50/p80/p95로 복제하지 않는다. 날씨가 없는 자료는 DRY가 아니다.
V11은 `bus_reference_chunk`, `bus_reference_row`만 추가하며 기존 V1~V10 및
`baseline_serving`/활성 버전은 변경하지 않는다. 현재는 내부 검토 조회 경로만 제공하며
적재 자체로 추천 소요시간·출발시각·실시간 적용 여부를 자동 변경하지 않는다.

## API
기존 X-DDUBI-API-KEY 및 /internal/ 접근 정책을 그대로 적용한다.

- POST `/internal/bus-references/{dataVersion}/chunks/{chunkId}`: JSON `{"rows":[...]}`. 청크0이상, 최대1000행.
- POST `/internal/bus-references/{dataVersion}/lookup`: 조회키 JSON. 최대100행, 초과시 truncated=true.

행 필드: masterVersion, namespace, routeId, fromStopId, toStopId, fromOrder,
toOrder, dow(일1~토7), hourSlot(0~23), sampleKind, meanSeconds,
sampleCount, observedDates, observationStart, observationEnd, sourceSha256, formulaInput(선택).
조회키는 masterVersion부터 hourSlot까지의 앞10개 필드다.
sampleKind는 HOURLY_SOURCE_MEAN, ACTUAL_TRAVERSAL_MEAN 또는 GTFS_SCHEDULE_FORMULA이며,
서로 다른 종류를 합산하지 않는다. 새 수식 버전의 formulaInput 구조와 계산 규칙은
`bus-travel-time-formula-fallback.md`를 따른다. Backend는 업로드 시 수식 재계산값과
meanSeconds의 일치를 확인하고 추천 평가에서도 formulaInput으로 다시 계산한다.
시간대 평균의 sampleCount는 원천 일자별 집계값 개수이지 버스 운행 관측 수가 아니다.
날씨·백분위·원천 가용시각·실시간·운영승인 필드는 만들지 않는다.

같은 dataVersion/chunkId 재전송은 내용 지문이 같을 때만 허용한다.
트랜잭션과 청크 행 잠금으로 중간 삽입 실패를 롤백하고 충돌 내용을 덮어쓰지 않는다.
조회는 버전·namespace·노선·정류장쌍·순서·요일·시간대를 모두 일치시킨다.
조회 결과가 있어도 STAGED_REFERENCE_NOT_APPROVED, productionReady=false,
realtimeApplied=false다. 서로 다른 출처/기간의 행은 별도 반환하며 자의적으로 평균내지 않는다.
한 청크 저장 성공이 전체 데이터셋 업로드 완료를 뜻하지 않는다. 활성화 API는 없다.

## 변환과 검증
`infra/scripts/export-bus-reference.py <stage3-result> <new-output>`는
완료manifest/출력 해시를 먼저 확인하고 SQLite를 읽기 전용으로 순차 읽는다.
원본/이전 산출물은 보존하며 1000행 단위 gzip JSON 및 export.json을 새 폴더에 기록한다.
압축을 해제한 JSON을 API body로 사용한다. 이 스크립트는 네트워크 업로드를 하지 않는다.
전체 실행 기본값은 제한 없음. `--max-chunks 1`은 계약 시험 전용이고 complete=false다.
파일 변경 시 입력manifest와 코드 해시로 dataVersion이 바뀐다.

## 미완료 운영 조건
격리 MySQL 8에서 V1~V11 적용 및 청크 재시도/충돌/정확키 조회를 검증했다.
내부 API 키가 있는 HTTP 저장·조회 및 키 없는 요청 거절까지 포함해 신규3개/기존인증5개 총8개 통합 테스트 통과.
서버 업로드/완료 검증, 참고값 공개·FE 표시, 추천 대체자료 적용 정책은 별도 후속이다.
마스터 유효일과 출처 가용시각 부족을 이 저장소가 해결해 주지는 않는다.
