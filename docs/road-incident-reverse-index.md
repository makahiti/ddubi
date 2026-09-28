# 사고 LINK_ID에서 버스 구간 찾기

## 연결 방식

| 단계 | 자료 | 다음 단계에 넘기는 값 |
| --- | --- | --- |
| 1 | BIS 노선별 경유노드(58) | 노선·정류장 순서로 `구간ID` 선택 |
| 2 | BIS 구간링크(62) | `구간ID`에 속한 BIS `링크ID` 목록 |
| 3 | BIS 공간 링크(63) | 각 링크의 시작·끝 노드와 연결 방향 |
| 4 | TOPIS 표준링크(7) | BIS 링크 ID·노드에 대응하는 서비스 `링크ID` |
| 5 | AccInfo 사고 API | 사건의 `LINK_ID`로 서비스 `링크ID`를 역조회하여 `segmentId` 목록 획득 |

`audit.py`가 1~4단계의 후보를 만든다. `preview_reverse_index.py`는 그 후보를
`LINK_ID → [segmentId, ...]`로 뒤집는다. 같은 `segmentId`가 여러 버스 occurrence에
등장하면 **모두 동일한 전체 도로 경로를 가질 때만** 역조회표에 넣는다. 일부만
연결되거나 경로가 다르면 `UNKNOWN_MAPPING`으로 취급한다. 사고 API의 LINK_ID는
도로 링크의 위치를 가리키며, 지연 시간이나 영향 범위를 직접 알려주지 않는다.

## 실행

공식 원본과 버스 crosswalk로 감사를 실행한 뒤, 완전한 AccInfo 수집 JSON을 입력한다.
아래 경로의 `data/`는 Git에서 제외된다. 원본 파일의 SHA가 바뀌면 다운로드 단계에서
중단하고 원천 자료를 재검토해야 한다.

```powershell
python backend/tools/road-impact/download.py --destination data/road-impact-current
python backend/tools/road-impact/audit.py `
  --directory data/road-impact-current `
  --bus-crosswalk backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz
python collector/src/sources/traffic_incident.py `
  --env-file infra/.env `
  --output data/road-impact-current/traffic-incidents.json
python backend/tools/road-impact/preview_reverse_index.py `
  --directory data/road-impact-current `
  --incident-snapshot data/road-impact-current/traffic-incidents.json `
  --output data/road-impact-current/reverse-preview.json
```

마지막 명령의 결과에는 `reverseIndex`, 사건별 `candidateSegmentIds`, 구간별
`candidateIncidentCountInSnapshot`이 있다. 연결되지 않은 구간의 건수는 `null`이다.
연결된 구간에서 0은 **이 수집 스냅샷에 일치하는 사건이 없었다**는 뜻이다.

## 적용 범위

출력에는 `REVIEW_ONLY_LINK_OVERLAP_NOT_APPROVED_ML_FEATURE`가 명시된다. 현재
감사 후보는 운영 승인 매핑이 아니다. 사고 시간·진행 방향·도로 자료 유효기간을
검증하지 않았으므로 이 결과를 곧바로 ML 학습 또는 서빙 feature로 쓰지 않는다.
학습에는 각 예측 시점까지 수집된 사고 이력만 결합하고, 운영에는 승인된
도로 매핑을 사용한다. 승인 매핑을 사용하는 실시간 역방향 조인 흐름은
`sparkjobs/streaming/road_incidents.py`에 있다.
