# 버스 참고자료 수량 검증(status) 지연 원인과 수정안 — 2026-09-17

## 관측한 사실 (운영 서버2, 읽기 전용)
- 쿼리: `SELECT count(*) FROM bus_reference_row WHERE data_version=?`. 실행 계획은 PRIMARY(data_version, chunk_id, row_index) 커버링 ref 스캔, 옵티마이저 추정 4,820,372행(실제 10,020,903행, 통계 오래됨).
- PRIMARY는 클러스터 인덱스라 payload JSON을 포함한 5,971MB 전체를 읽는다. 보조 인덱스 `ix_bus_reference_lookup`은 2,106MB인데 선택되지 않았다. 테이블 총 8,077MB, 평균 행 649B.
- 실행 시간: performance_schema 기준 4회 평균 112초, 최대 178초, 오류 0. 청크 COUNT는 0.1초, 정확키 조회는 0.3초.
- DB 자원: innodb_buffer_pool_size 256MB(16,384페이지), 컨테이너 메모리 제한 1GB, 호스트 여유 약 11.7GB. 콜드 캐시에서 매번 디스크 읽기.
- 호출 구조: 내부 status API는 요청마다 전체 COUNT를 실행한다. gate는 기동 시 1회 실행 후 성공을 프로세스 수명 동안 캐시하고 실패면 60초마다 재시도한다. 업로더 완료 확인(60초)과 진단 헬퍼(120초)가 status API에 의존해 타임아웃됐다.
- 결론: 버퍼풀 크기만의 문제가 아니다. 보조 인덱스를 강제해도 약 2.1GB 스캔이며 요청 경로에 O(N) 카운트를 두는 구조가 원인이다. 타임아웃 연장은 해결이 아니다.

## 요청마다 카운트 vs 기동 시 검증
- 요청 경로(status, gate 재시도)는 전체 COUNT를 하면 안 된다.
- 전체 COUNT는 "불변 데이터 버전을 1회 검증"할 때만 실행하고, 그 결과를 버전에 연결해 재사용한다.
- 재사용은 검증 후 데이터가 바뀌지 않았다는 보장이 있을 때만 유효하므로 동결과 드리프트 검사가 함께 필요하다.

## 수정안 (V12 마이그레이션, 승인 후 진행)
1. 테이블 `bus_reference_dataset_verification`: data_version(PK), expected_rows, expected_chunks, counted_rows, counted_chunks, chunk_row_sum, min_chunk_id, max_chunk_id, method('FULL_COUNT'), verified_at, verified_by, frozen.
2. `bus_reference_chunk.row_count INT NULL` 추가. 10,021행 테이블이라 즉시 DDL. 검증 시 `COUNT(*) ... GROUP BY chunk_id`로 채우고, 이후 청크 업로드 트랜잭션은 행 삽입과 같은 트랜잭션에서 기록한다.
3. 내부 `POST /internal/bus-references/{version}/verify` 본문 {expectedRows, expectedChunks}: 전체 COUNT(보조 인덱스 강제), 청크 COUNT, MIN/MAX chunk_id, per-chunk row_count 합계를 계산한다. 통과 조건: counted_rows==expected_rows, counted_chunks==expected_chunks, chunk 수==max-min+1이고 min==0(연속), SUM(row_count)==counted_rows. 통과하면 frozen=true로 저장한다. 비동기 실행과 진행 상태 조회를 제공한다.
4. 업로드: frozen 버전으로의 청크 업로드는 409로 거절한다. 검증 후 수정·추가는 새 dataVersion으로만 한다.
5. status: 검증 레코드가 있으면 즉시 반환(verified=true, counted 값, verified_at)하고, 매 호출마다 저렴한 실시간 검사(청크 COUNT, SUM(row_count), MIN/MAX)를 저장값과 비교한다. 하나라도 다르면 verified=false와 DRIFT_DETECTED. 레코드가 없으면 verified=false, verifyRequired=true를 주고 전체 COUNT는 하지 않는다.
6. gate: verified==true, expected 일치, 드리프트 없음일 때만 통과한다. 기동 시 수 ms. 전체 COUNT는 검증 1회에만 발생한다.
7. 오탐 방지: 부분 적재는 연속성·합계 불일치로 실패, 재시도는 기존 지문 동일 조건 유지, 누락 청크는 연속성 검사, 다른 버전은 PK로 분리, 검증 이후 변경은 frozen 거절과 드리프트 검사로 차단한다. 청크 합계만으로 행 수 검증을 대체하지 않는다. 최초 검증은 반드시 전체 COUNT다.
- 한계: DB 직접 조작으로 행 수를 유지하면서 내용만 바꾸는 변경은 탐지하지 못한다. 운영 절차로 금지하고 재검증 명령을 제공한다.

## 변경 영향과 롤백
- 영향: 테이블 1개 추가, 10,021행 테이블 컬럼 1개 추가. 1천만 행 테이블은 변경하지 않는다. backend 재시작 1회. 검증 1회 실행 약 2~3분(보조 인덱스 강제 시 단축 예상).
- 롤백: 코드 롤백 시 새 테이블과 컬럼은 남겨도 무해하며 Flyway 이력을 보존한다. 데이터 변경 없음. 기존 V1~V11은 수정하지 않는다.
- 대안(별도 인프라 결정): 버퍼풀 증설(예: 1~2GB)은 IO를 줄이지만 O(N) 구조와 콜드 캐시 문제는 남는다.

## 이번에 먼저 반영한 것 (스키마 변경 없음)
- gate 결과·사유·관측 수량을 `gateStatus()`로 노출하고 기동 로그 한 줄을 남긴다. 상세는 contracts/bus-historical-fallback.md의 "gate 관측".
