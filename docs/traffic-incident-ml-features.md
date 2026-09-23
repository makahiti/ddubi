# 돌발 정보 → 버스 구간 ML 특성

## 데이터 흐름

| 순서 | 입력 | 처리 | 출력 |
|---|---|---|---|
| 1 | OA-13315 `AccInfo` 전체 조회 | Collector가 기존 개별 돌발 이벤트와 함께, 완전한 회차에 한해 전체 목록 스냅샷 이벤트 발행 | `city.raw`의 `traffic.incident.snapshot` |
| 2 | 전체 목록 스냅샷 | Silver가 사고 ID를 중복 제거하고 승인된 `SEOUL_ACCINFO_LINK` 마스터로 링크를 검증 | `traffic:latest:incident-snapshot:all-current` (최대 30분) |
| 3 | 버스 `segmentId` | 승인된 `ml:mapping:bus-road:active:{masterVersion}`에서 해당 구간의 `topisLinkIds` 조회 | 구간에 속한 검증된 링크 목록 |
| 4 | 검증된 링크 목록 + 최신 스냅샷 | ML 최신 특성 조립기가 링크 ID를 비교하고 중복 사건을 한 번만 집계 | `bus-segment-feature-v3` 현재 Snapshot |

수집 회차가 부분 성공이거나 스냅샷 이벤트가 Kafka 크기 제한(800 KB)을 넘으면 새 스냅샷을 발행하지 않는다. 이전 스냅샷은 30분 후 만료된다. 지연 도착한 오래된 회차가 최신 Redis 값을 덮지 못하게 막는다.

## 특성

| 값 | 의미 | 사용할 수 없을 때 |
|---|---|---|
| `verifiedIncidentCount` | 이 구간의 **승인된 링크**와 일치한 고유 돌발 건수. `0`은 검증된 링크에서 확인된 사건이 0건이라는 뜻이다. | `null` |
| `incidentUnmatchedCount` | 같은 전체 수집 회차에서 링크를 확인하지 못한 돌발 건수. 연결 범위의 한계를 보여준다. | `null` |
| `incidentDataAvailable` | 완전한 최신 수집 회차, 승인된 링크 마스터, 유효한 구간 매핑이 모두 맞는지 | `false` |
| `incidentMinAgeMinutes` | 일치한 돌발 중 가장 최근 사건이 시작된 지 지난 시간(분) | 일치 사건이 없거나 데이터가 없으면 `null` |
| `incidentSnapshotAgeMinutes` | 전체 수집 회차를 관측한 지 지난 시간(분) | `null` |

`accidentCount`, `closureCount`, `assemblyCount`, `protestCount`는 현재 `null`이다. OA-13315의 유형 코드표와 실제 사례를 검증한 뒤 분류해야 한다. `expectedClearAt`은 종료 예상 시각이므로 실제 사건 종료로 간주하지 않는다. 현재 목록 스냅샷이 제공하는 관측 시점 기준으로만 집계한다.

## 운영 조건과 한계

- 기본 운영은 엄격한 단일 경로 후보를 별도 `PROVISIONAL` 대응표로 사용할 수 있다. 이 값은
  경로 상태 설명용 추정 근거이며 정식 `RESOLVED`와 구분한다. 대응표가 없거나 만료되면 같은 숫자
  링크 ID를 임의로 연결하지 않는다.
- 현재 승인 매핑이 0건이면 `verifiedIncidentCount=null`, `incidentDataAvailable=false`가 정상이다. 실데이터 연결률을 측정하고 매핑을 승인해야 수치가 채워진다.
- 링크를 확인하지 못한 돌발이 남아 있으면 `verifiedIncidentCount`는 전체 영향 건수의 **하한**일 수 있다. ML 학습에는 `incidentUnmatchedCount`, `incidentDataAvailable`을 함께 넣고, 이 수만으로 지연 여부를 단정하지 않는다.
- `bus-segment-feature-v3`는 현재시점 온라인 특성 계약이다. 설명문 GMS 추출값은 추출시각이 `asOf` 이후면 사용하지 않는다. 기존 학습 행 v1이나 배포 모델에 자동으로 입력되지 않는다. 과거 시점의 완전한 수집 회차와 당시 승인 매핑으로 같은 규칙의 학습 행을 만든 뒤 모델을 재학습해야 한다.
- 승인 도로 대응표가 배포되면 `ml-incident-feature-refresh`가 새 전체 돌발 스냅샷을 감지해 영향받은 구간의 CURRENT 특성을 다시 조립한다. 사고가 사라진 구간도 재계산한다. 활성화 순서는 [운영 절차](runbooks/incident-segment-activation.md)를 따른다.
