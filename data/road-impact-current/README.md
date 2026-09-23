# 버스 구간 ↔ 도로 LINK_ID 감사 자료

이 디렉터리는 서울 버스 인접 정류장 구간과 BIS/TOPIS 도로 링크를 연결한 **검토용** 입력과
산출물을 함께 보존한다. 모든 결과는 `REVIEW_REQUIRED`이며 운영 승인 자료가 아니다.

## 현재 기준 결과

- 버스 마스터: `METRO-BUS-20260917-SEOUL-0902`
- 서울 버스 occurrence: 40,959개
- BIS 방향 경로 후보: 24,377개
- TOPIS 전체 연결 후보: 13,824개
- 운영 승인: 0개

`mapping-candidates.jsonl`은 `segmentId`별 확정표가 아니라 occurrence별 검토 후보다. 동일
`segmentId`의 모든 occurrence가 같은 경로인지, AccInfo LINK_ID namespace가 같은지, 적용기간과
진행방향이 유효한지 추가 검증하기 전에는 `RESOLVED`로 사용하지 않는다.

## 파일 역할

| 파일 | 역할 |
|---|---|
| `sources.json` | 현재 감사 실행에 사용한 네 원본과 SHA-256 |
| `tdata-58-10251.csv` | BIS 노선별 경유 노드 |
| `tdata-62-10255.csv` | BIS 구간별 링크 순서 |
| `tdata-63.csv` | BIS 링크 시작·종료 노드와 사용 여부 |
| `tdata-7.csv` | TOPIS 표준 링크 대응 |
| `mapping-candidates.jsonl` | 연결된 occurrence 24,377건의 상세 후보 |
| `mapping-rejected.jsonl` | 연결되지 않은 occurrence와 실패 사유 |
| `mapping-audit.json` | 전체 건수와 입력 버전·SHA 요약 |

## 재현 명령

프로젝트 루트에서 실행한다.

```powershell
python backend/tools/road-impact/audit.py `
  --directory data/road-impact-current `
  --bus-crosswalk backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz
```

이 명령은 `sources.json`의 네 파일 SHA를 검증한 뒤 다음 파일을 다시 만든다.

- `mapping-audit.json`
- `mapping-candidates.jsonl`
- `mapping-rejected.jsonl`

## GitLab 보관

`data/`의 다른 실행 데이터는 계속 무시하고 이 디렉터리의 재현 필수 파일만 예외로 추적한다.
CSV와 JSONL은 Git LFS 대상이다. clone/pull 전에 Git LFS가 설치되어 있어야 하며,
GitLab 프로젝트에서 LFS가 활성화되어 있어야 한다.
