# 수도권 버스 마스터 적용

```text
backend/transit-master/
├─ bus/              # 버스 마스터·구간 ID 대응표·개별 manifest
├─ subway/           # 지하철 마스터·좌표·출처
├─ road/             # 도로 대응표 검증 상태
├─ manifest.json     # 공통 버전·SHA·호환 조합
├─ manage.py         # 무결성 검사·환경값 출력
├─ distribution.md   # 공통 배포 안내
├─ validation/       # 기존 검증 기록
└─ README.md
```

배포 원본은 `backend/transit-master/bus/`로 이동했다. 공통 manifest와 배포 설정은
[공통 마스터 안내](distribution.md)를 따른다.

`metropolitan-bus-v6.json.gz`는 통합 CSV의 1,578개 출처별 노선·106,650개 정류장 순번과 공식 형상을 묶은 서빙 artifact다. 노선별 중복 출처가 있으므로 1,578을 고유 운행 노선 수로 해석하지 않는다. 숫자 ID·선행 0·미정차 경유점·결측값·방향은 원본대로 보존한다.

## 적용 상태

- Java 매핑/관리 적재/별도 버전 캐시가 구현되어 있다.
- BUS 요청은 항상 수도권 매핑기를 사용한다. 마스터가 미적재이면 `UNMAPPED/MASTER_DATA_NOT_CONFIGURED`를 반환하며 서울 전용 매핑기로 돌아가지 않는다.
- 버스 새 버전을 적재해도 기존 서울/지하철 마스터와 활성 포인터는 바꾸지 않는다.
- DB에 적재하기 전 Flyway V4 및 ERD 변경을 Backend 담당과 리뷰한다. 이 작업에서 운영 DB나 배포 설정은 변경하지 않았다.
- ML 입력/결과 검증 포트와 전체 버스 step 평균 합산은 구현되어 있다. 실제 GPU 모델·학습 ID 대응 어댑터는 아직 없으므로 기본 응답은 카카오 fallback이다.

## 1. 관리 적재

먼저 V4 마이그레이션이 적용된 Backend DB를 준비한다. 기존 Backend 실행 환경의 DB 설정을 사용하고 다음 환경변수를 설정하여 한 번 관리 기동한다.

```text
DDUBI_METROPOLITAN_BUS_IMPORT_ENABLED=true
DDUBI_METROPOLITAN_BUS_ARTIFACT=/absolute/path/metropolitan-bus-v6.json.gz
DDUBI_METROPOLITAN_BUS_SHA256=<metropolitan-bus-v6.json.manifest.json의 sha256>
```

`MetropolitanBusMasterService`가 전체 SHA와 데이터 품질을 확인한 다음 DB에 저장하고 활성 포인터를 교체한다. 같은 버전·같은 파일은 멱등, 같은 버전·다른 파일은 오류다. 실패하면 트랜잭션이 롤백되어 기존 버전을 유지한다. CSV 수집기나 Spark가 이 DB에 직접 쓰지 않는다.

관리 기동 후 `DDUBI_METROPOLITAN_BUS_IMPORT_ENABLED=false`로 되돌린다. 이후 일반 기동은 DB artifact를 한 번 읽고 메모리에서 매핑한다. 관리 파일 경로는 일반 요청에 필요하지 않다. 다른 인스턴스는 기본 60초 주기로 활성 버전을 확인한다.

## 2. 결과 사용

경로 검색의 기존 `mapping` 객체에 `busAlternatives`가 추가된다. 차량별로 공식 출처 키, 정류장 순서와 좌표, ID 확인 상태와 공간 검증 사유를 받는다. 서울·경기·인천의 단일 검증 대안/출처는 canonical-adjacent-v2의 EXACT/segments를 발급한다. 여러 버스 또는 여러 출처 후보는 PARTIAL과 대안별 결과를 반환한다. Backend가 차량을 선택하기 전에 서로 다른 대안의 edge를 합산하지 않는다.

대표 예시(필드 일부):

```json
{
  "status": "PARTIAL",
  "segments": [],
  "busAlternatives": [{
    "providerName": "1007",
    "identityStatus": "CONFIRMED",
    "geometryStatus": "CONFLICT",
    "reason": "ENDPOINT_TOO_FAR",
    "predictionReady": false,
    "candidates": [{"namespace": "GBIS", "routeId": "234000015"}]
  }]
}
```

전체 응답 계약은 `docs/contracts/metropolitan-bus-mapping.md`를 따른다. 검토 날짜가 지난 예약·분기 별칭은 자동 연장하지 않는다. `2026-09-09`는 과거 검증을 재현하는 기준일이며 운영 날짜를 이 값으로 고정하면 안 된다. 정확한 좌표 패턴의 짧은 A-B-A 형상 보정은 매번 공식 형상/거리 조건을 다시 확인하므로 날짜 별칭과 다르게 처리한다.

## 3. 파일 재생성

원본 다운로드와 카카오 1~20.json은 바탕화면 `경로` 폴더에 그대로 둔다. 저장소 루트에서 실행:

```powershell
python backend/tools/build_metropolitan_master.py --source 'C:/Users/SSAFY/Desktop/경로/카카오화면대조_매핑보강_v6' --responses 'C:/Users/SSAFY/Desktop/경로' --output backend/transit-master/bus/metropolitan-bus-v6.json.gz
```

다른 데이터를 배포할 때는 원본 메타데이터 version도 새 값으로 지정한다. 검토 대응표는 마스터 해시·검토 범위를 다시 확인해야 하며 변환기가 기존 검토를 새 마스터에 자동 승인하지 않는다.

## 4. Java 검증

Java 17과 Maven 래퍼를 사용한다. 아래 명령은 `backend/`에서 실행하며 테스트 결과는 `target/`에 남는다.

```powershell
./mvnw.cmd '-Dtest=MetropolitanBus*Test,ProviderSegmentSignatureFactoryTest,MasterDataTransitMappingResolverTest,SubwaySegmentMatcherTest,SeoulTransitMasterImportServiceTest,CanonicalSegmentIdFactoryTest,MappingCorpusEvaluatorTest,KakaoRouteMapperTest' test
./mvnw.cmd -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/mapping-classpath.txt'
$mappingClasspath = 'target/classes;' + (Get-Content target/mapping-classpath.txt -Raw).Trim()
java '-Dfile.encoding=UTF-8' -Xmx1800m -cp $mappingClasspath com.ssafy.ddubi.domain.route.mapping.MetropolitanBusReplay transit-master/bus/metropolitan-bus-v6.json.gz 'C:/Users/SSAFY/Desktop/경로' 'C:/Users/SSAFY/Desktop/경로/카카오화면대조_매핑보강_v6/mapping-results.json' 2026-09-09 target/metropolitan-replay-reviewed.json negative
```

기준일을 `2026-09-10`으로 바꾸고 다른 output 파일에 실행하면 날짜가 지난 별칭을 제외한 결과를 확인한다. `negative`를 붙이면 역방향·좌표 이동·미등록 노선·전체 이름 변경·0 길이의 총 1,405건을 검사한다. Replay는 별도 Matcher로 재계산하고 카카오 DTO → Mapper → Resolver의 결과와도 전체 대안 목록을 비교한다. v6 결과 JSON은 사후 비교에만 사용하고 매핑기에 주입하지 않는다.

Windows Maven 래퍼가 `Target[0]` 오류로 시작되지 않으면 환경의 `.m2` 경로 처리 문제다. 검증 시에는 임시 Junction을 `MAVEN_USER_HOME`으로 지정해 래퍼를 실행했고 래퍼 원본과 시스템 설정은 바꾸지 않았다.

## 기존 버스 코드 정리 (2026-09-10)

서울 전용 Matcher·버스 읽기 모델·JDBC 버스 조회·XLSX 적재와 Apache POI 의존성을 제거했다.
예전 버스 corpus 생성 도구는 통합 artifact 변환기와 `MetropolitanBusReplay`로 대체했다.
과거 corpus의 입력 형식은 테스트 소스의 Legacy fixture에만 남아 있으며 실행 산출물에는 포함되지 않는다.

`SeoulTransitMasterImportService/Runner`와 `SeoulTransitMasterDataProvider`는 기존 지하철 테이블·활성 포인터를 관리하므로 유지한다.
지하철 적재에는 시간표 CSV와 선택 실시간 대응표·지하철 별칭만 사용한다. BUS 별칭은 이 적재기에서 거절한다.
기존 V1~V3 및 서울 버스 DB 테이블·저장된 이력은 수정·삭제하지 않는다. 버스 캐시는 V4의 전용 테이블만 사용한다.

기존 서비스 사용 플래그와 150m 반경 설정은 제거했다. 환경에 남아 있어도 적용되지 않으므로 제거하면 된다.
관리 적재 플래그는 일반 기동의 임의 DB 쓰기를 막기 위해 유지한다. 배포 전에는 V4 적용과 artifact 관리 적재가 필요하다.

## 공통 대응표·예측·저장 연결

같은 변환기가 `metropolitan-bus-v6.crosswalk.json.gz`와 manifest를 함께 만든다.
현재 대응표는 105,070개의 순번별 인접 edge를 포함하며 원본 순번 단절 2개는 연결하지 않았다.
Collector와 Spark의 driver에 파일을 배포하고 다음 환경변수를 동일하게 설정한다.
Spark는 `.env`를 자동 로드하지 않으므로 잡 실행 환경에 직접 전달한다.

```text
BUS_CROSSWALK_PATH=/absolute/path/metropolitan-bus-v6.crosswalk.json.gz
BUS_CROSSWALK_SHA256=e7b8db4f3330d7275aa56b4674032d71aa6e34f99bec0c29cee9f7ce002676bd
```

파일을 새로 만들었으면 위 예시 SHA를 재사용하지 말고 새 manifest 값을 사용한다.
Collector/Spark는 기동 시 SHA·버전·전체 ID를 검증하고 불일치한 파일로 기동하지 않는다.
미설정 상태는 관측 수집을 유지하되 BUS canonical ID를 비운다. 위치 관측의 `sectOrd`만으로는 매핑하지 않는다.

Spring `BusPredictionPort`에 승인된 모델 어댑터를 등록하면 검색 응답의 `busEvaluations`에서
대안별 전체 구간 평균을 제공한다. 입력 누락·미승인·만료·부분 결과는 전체 step fallback이다.
`RouteSegmentEdgeWriter`는 상위 저장 서비스가 소유권 검증 후 호출해야 한다. 검색은 경로를 저장하지 않는다.
상세 호출 계약과 소비 예시는 `docs/contracts/metropolitan-segment-integration.md`를 참고한다.

추가 검증은 `BusJourneyEvaluatorTest`, `RouteSegmentEdge*Test`, `TransitContractSyntaxTest`,
Collector/Spark의 `test_bus_crosswalk.py`를 실행한다. DB 테스트는 Docker가 필요하고,
Windows Spark의 Parquet 테스트에는 Hadoop 실행 환경이 별도로 필요하다.
