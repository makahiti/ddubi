# 수도권 지하철 마스터 재생성

이 스크립트는 카카오 응답을 마스터 생성에 사용하지 않는다. 공식 원본을 정규화한 작업 폴더를 입력받고 결과를 같은 폴더에 쓴다. `openpyxl`이 필요하다. 네트워크·DB·Redis I/O는 없다.

```powershell
python backend/tools/subway-master/build_enriched.py <작업폴더>
python backend/tools/subway-master/package_master.py <작업폴더> <실시간도착_역정보.xlsx>
```

작업 폴더 입력:

- `kric-stations.json`, `kric-operations.json`: 공식 XLSX의 행 배열. 첫 행은 헤더이며 ID 문자열의 앞자리 0을 보존한다.
- `seoul-topology.json`: 기존 `SeoulTransitMasterImportService.readSubwayTimetable`의 순수 파싱 결과. DB 적재 전의 lines/stations/lineStations.
- `seoul-stations-normalized.json`: TOPIS OA-21232의 전체 list와 page 메타데이터.
- `raw/gtxa-main.html`: 운영사의 두 운행 구간 카드. HTML 형식이 달라지면 파서 검토가 필요하다.
- `raw/` 아래 계약 문서의 원본 파일과 `.source.json`: 출처 URL·SHA-256. 실험 원본과 재생 스크립트는 바탕화면 `경로/지하철_보강검증_20260910`에 보존한다.

출력은 `metropolitan-subway-master.json.gz`, `source-manifest.json`, `station-coordinates.json`, `crosswalk.json`, `master-gaps.json`, 원본 격리/패턴 근거 JSON이다. gzip 시각은 0으로 고정한다. 생성 후 공식 원본 해시와 golden corpus를 다시 검증하고, 검토된 세 배포 파일만 저장소 루트의 `backend/transit-master/subway/`로 반영하고 공통 manifest를 갱신한다. Maven이 기존 classpath로 포함한다.

역명 축약 대응은 KRIC 운행 자료에만 적용한다. 다른 이름을 전역 유사도 매칭하거나 역 코드 크기로 방향을 추정하지 않는다. 파일 전체 버전 고정과 배포 방법은 [계약](../../../docs/contracts/metropolitan-subway-master.md)을 따른다.
