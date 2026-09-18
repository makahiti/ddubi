# 수도권 공통 마스터

원본 배포 파일은 이 폴더에서만 관리한다. 버스는 `bus/`, 지하철 마스터·좌표·출처는
`subway/`, 도로 대응표 상태는 `road/`에 둔다. 최초 파일 이동 당시에는 내용이 같았으며,
2026-09-17 버스 master/crosswalk는 공식 누락 노선을 보충해 새 버전·SHA로 갱신했다.
현재 기준은 [갱신 계약](../../docs/contracts/transit-data-refresh.md)과 `manifest.json`이다.
버스 생성 및 과거 검증 상세는 [기존 안내](README.md)를 참고한다.

`manifest.json`은 파일별 버전·압축 바이트 SHA-256·출처 문서·기준일과 호환 조합을 기록한다.
단일 기준일이 확인되지 않은 파일은 `referenceDate=null`이며 원천별 날짜를 출처 문서에서 확인한다.
버스 master와 crosswalk는 본문에 기록된 SHA/버전 관계까지 검증한다. 지하철은 독립 ID 공간이며
같은 배포 묶음에 포함됨을 뜻한다. 버스와 지하철 ID가 직접 대응한다는 의미는 아니다.

프로젝트 루트에서:

```powershell
python backend/transit-master/manage.py check
python backend/transit-master/manage.py env
# 컨테이너 안에서 사용할 경로로 환경값을 출력
python backend/transit-master/manage.py env --mount-root /opt/ddubi/transit-master
```

Collector와 Spark에는 출력된 동일한 `BUS_CROSSWALK_PATH`, `BUS_CROSSWALK_SHA256`을 적용한다.
Backend에는 `DDUBI_METROPOLITAN_BUS_ARTIFACT`, `DDUBI_METROPOLITAN_BUS_SHA256`,
`DDUBI_TRANSIT_MAPPING_SUBWAY_MASTER_RESOURCE`를 적용한다. `check` 실패 시 배포하지 않는다.
갱신 시 원천/호환성을 검토하고 `manage.py build`로 manifest를 다시 만든 뒤 함께 배포한다.
`build`는 변경 파일을 신뢰하도록 manifest를 갱신하는 명령이므로 배포 검증 대신 사용하지 않는다.

Backend Maven은 `subway/`를 `target/classes/transit/`에 복사한다. 기존
`classpath:transit/metropolitan-subway-master.json.gz` 설정은 그대로 사용할 수 있다.
Maven은 Backend 내부의 `transit-master/subway/`를 읽는다.
깨끗한 빌드에서 리소스 누락을 방지하려면 먼저 manifest 검증을 실행한다.

Compose는 기존 `backend/transit-master/` 마운트를 유지하며 버스 파일 경로에 `bus/`를 포함한다.
Backend Docker 빌드는 `transit-master/subway/`를 복사한 뒤 Maven으로 기존 classpath에 포함한다.
운영 환경변수에 예전 파일 경로를 명시했다면 다음 경로로 갱신해야 한다.

- `BUS_CROSSWALK_PATH=/opt/ddubi/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz`
- `DDUBI_METROPOLITAN_BUS_ARTIFACT=/opt/ddubi/transit-master/bus/metropolitan-bus-v6.json.gz`

Compose의 Spark는 선택 실행 설정을 보존한다. 활성화한 환경에서는
`SPARK_BUS_CROSSWALK_PATH`도 위 BUS 경로로 갱신하고 `SPARK_BUS_CROSSWALK_SHA256`에는
같은 대응표 SHA를 지정한다. 빈 설정을 이번 경로 변경만으로 자동 활성화하지 않는다.

2026-09-17 버스 파일 내용과 SHA가 변경됐으며, 사용자 요청으로 운영 DB 활성화와 양 서버 파일,
버스 수집기 2개·Spark realtime의 데이터 설정 반영을 완료했다. Spark 인덱스 캐시 구현을 확인해
crosswalk도 활성화했고 Redis의 새 버전 결과까지 검증했다. BE 코드 자체는 기존 운영 버전이다.
운영 설정에는 새 SHA가 고정돼 있으므로 다음 정식 배포에도 이 데이터 버전을 포함해야 한다.
검증 시각·범위·복구 위치는 [갱신 계약](../../docs/contracts/transit-data-refresh.md)을 따른다.
