# 3단계 전체 적재 및 과거 평균 계산 연결

## 배포 대상과 경계
- 기존 feature/frontend/S15P21E202-46-fe-infra-correction → fe/dev → fe/main → develop 단계별 MR/CI.
- V11은 참고자료 2개 테이블만 추가한다. 적용된 V1~V10을 변경하거나 삭제하지 않는다.
- 버스 자료를 운영 DB에 저장하는 것, 과거 평균 fallback을 켜는 것, 실시간/ML 운영승인은 서로 다르다.
- 지하철 현행 운행일과 도로 통제는 미확인이다. 패키지 조회를 해당 승인으로 처리하지 않는다.

## 확정 자료
- dataVersion: `bus-ref-3e2b388cdbb2beefd3a7b75e`
- masterVersion: `METRO-BUS-20260917-SEOUL-0902`
- 버스 10,020,903행 / 10,021청크: 시간대 원천 평균 9,953,725, 실제 운행 평균 67,178. 관측 종류를 혼합 집계하지 않는다.
- 지하철 105,670개 / 도로 40,264개, 512샤드 전건 검증.
- 지하철 manifest SHA256: `a90f06ab4d059981aa36aff743fb100908ec651e0cde3b6ec65d6945669a53f9`
- 도로 manifest SHA256: `ae9431562b135c42aa79abc94e459ef79c338573e16eda81a319ef02c7e1c1bd`
- 서버2 준비 경로: `/home/ubuntu/ddubi-stage3-20260917`. 파일 전송·검증만으로 DB 적재 완료라 하지 않는다.

## 실행 순서
1. 해당 feature의 로컬 검증과 CI 통과 후 MR을 순서대로 병합한다. develop 최종 CI와 실제 배포 커밋을 대조한다.
2. 서버 기존 마스터 변경은 별도 백업에 보존하고 대상 버전과 비교한다. 기존 `.env`의 다른 항목은 유지한다.
3. fallback.enabled=false 상태로 BE/V11을 배포하고 Flyway 이력·서비스 상태를 확인한다.
4. `transfer-bus-reference.py <folder>`로 서버 파일 전건을 검증한다. 기본 실행은 네트워크/DB 변경이 없다.
5. 서버에서 `sudo python3 upload-bus-reference-local.py <folder> <checkpoint>`를 실행한다. backend 네트워크의 loopback을 사용하며 포트를 외부에 열지 않는다. 내부 API 키는 메모리에서만 전달한다.
6. 중단되면 동일 자료·체크포인트로 재개한다. 청크 ACK마다 원자적 체크포인트 저장. 최종 내부 status가 정확히 10,020,903행/10,021청크일 때만 완료다.
7. 패키지는 기존 읽기 전용 transit-master 마운트 안 `reference-library`에 배치하고 manifest 경로와 SHA를 함께 설정한다.
8. 버스 버전, expected-rows=10020903, expected-chunks=10021를 지정한 뒤 fallback.enabled=true. 기본 정책은 최근90일/최소표본3/관측일3/최대미래7일/HOURLY_SOURCE_MEAN. 이 값은 품질보장치가 아니라 초기 적용 제한이다.
9. 정확키 조회, 실제 Journey 계산의 적용/미적용 사유, 예상시각 변화와 FE 표시를 확인한다. 로그의 healthy/401은 추천 계산 검증을 대신하지 않는다.

## 적용 및 롤백
- ML 실패 시 정확 매핑된 BUS RIDE 전체에만 과거 평균을 적용한다. 부분 적용/원천 혼합/백분위 조작은 하지 않는다. API 잔여시간은 기존 계산과 동일하게 한 번만 유지한다.
- 자료가 부족하거나 마스터가 달라지거나 시각이 불확실하면 API 시간으로 복귀한다. 실시간 대기는 기존 시간분해 검증 조건을 유지한다.
- fallback을 끄면 코드상 API/기존 ML 경로로 복귀한다. 자료와 V11 이력은 삭제하지 않는다.
- 코드 롤백 때도 V11 파일·테이블·Flyway 이력을 보존한다. 기존 이미지와 마스터 백업을 확인한 뒤 수행한다.
- 실제 서버 적재 완료·활성화·기기 검증은 실행 증거가 확보될 때 상태 문서에 기록한다. 본 문서는 절차이지 배포 완료 증거가 아니다.
