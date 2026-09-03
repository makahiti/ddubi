# internal-api.yaml 계약 검토 회신

회신: 백엔드(서비스) 손정민 → 인프라
대상: `docs/contracts/internal-api.yaml` (`e00b7c9`), `docs/meetings/2026-08-31-backend-share.md`

---

## 결론

**방법 2(HTTPS API 경유)에 동의합니다.** 계약 초안도 대체로 그대로 갑니다.

구현량은 백엔드가 더 지지만 얻는 게 큽니다.

- `baseline_serving` 의 스키마·적재·버전 전환을 한 팀이 소유하게 됩니다
- 틀린 값을 입구에서 막습니다. 검증 없이 들어오면 추천이 **에러 없이 조용히** 틀립니다
- ERD 에만 있고 트리거가 없던 `confidence` 강등과 `DATA_STALE` 알림이 "마지막 활성화 시각"으로 성립합니다

계약 설계도 잘 맞물립니다. **upsert 로 청크 재시도 안전 + activate 분리로 부분 반영 방지 + 직전 버전 보호로 롤백 가능** — 25만 행을 50번 나눠 보내다 중간에 끊겨도 이전 버전이 계속 서빙됩니다.

아래는 **바꿔야 할 것 1건, 회신 1건, 수정 요청 3건**입니다.

---

## 1. ✅ `dow` 정의 — 해결 (2026-09-01 합의)

**Spark 기준(1 = 일요일)으로 통일**하기로 확정했습니다. 백엔드가 반영 완료:

- `docs/ddubi_erd_v2.sql` · `V1__init.sql` 주석을 `일=1, 토=7 (Spark dayofweek 기준)` 으로 수정
- 변환은 `BaselineDayOfWeek` 유틸 한 곳에 가둠 (`global/util`). 호출부에서 직접 계산 금지
- 요일 7개 왕복 변환 테스트 16건 추가

**계약서(`internal-api.yaml`)는 수정 불필요**합니다 — 이미 Spark 기준으로 적혀 있었고 그쪽이 맞았습니다.

<details>
<summary>원래 안건 내용 (기록용)</summary>

가장 급한 항목입니다.

| 문서 | 정의 |
| --- | --- |
| `internal-api.yaml` | `dow: "요일 1(일)~7(토). Spark dayofweek 와 동일"` |
| `docs/ddubi_erd_v2.sql:342` | `dow TINYINT COMMENT '요일 1~7 (월=1)'` |

**`1`이 일요일이냐 월요일이냐가 다릅니다.**

어긋난 채로 돌면 **월요일 출근길에 일요일 기준선을 씁니다.** 일요일은 한산해 소요시간이 짧게 나오고, 결과적으로 **늦게 출발하라고 추천해 지각시킵니다.** 예외도 로그도 남지 않아 발견이 매우 늦습니다.

### 제안: Spark 기준(1 = 일요일)으로 통일

| 안 | 내용 | 평가 |
| --- | --- | --- |
| **A. Spark 기준 채택** | ERD 주석을 `1=일요일`로 수정. 백엔드가 `LocalDate.getDayOfWeek()`(1=월) 에서 변환 | **권장** |
| B. ERD 기준 유지 | Spark 배치가 `dayofweek` 결과를 보정 | 배치에 변환 코드 추가 |

A 를 권하는 이유: 데이터를 **만드는 쪽의 관례를 그대로 두고**, 변환은 **읽는 쪽 한 곳(백엔드)** 에서만 합니다. 양쪽에서 변환하면 언젠가 한쪽이 빠집니다.

확정되면 백엔드가 **DDL 주석과 계약서 양쪽에 `1 = 일요일`을 명시**하겠습니다.

</details>

---

## 2. 회신 — 요청하신 UNIQUE 인덱스는 이미 있습니다

> baseline_serving 유니크 인덱스: (segment_id, dow, hour_slot, weather_bucket, data_version)에 유니크 인덱스 권장

`docs/ddubi_erd_v2.sql:353` 에 이미 들어가 있습니다.

```sql
UNIQUE KEY UK_BASELINE_KEY (segment_id, dow, hour_slot, weather_bucket, data_version)
```

계약서의 "같은 키 재전송은 upsert 로 처리해 청크 재시도가 안전하다"는 전제가 **이미 만족**됩니다. 추가 작업 없습니다.

---

## 3. 계약 수정 요청 3건

### 3-1. 응답을 공통 래퍼로 감싸주세요

현재 계약:

```yaml
"200": '{"accepted": 5000, "dataVersion": "2026-09-01"}'
```

뚜비는 **모든 API 응답을 공통 형식으로 감쌉니다**(API 명세서 v2.1.1 §2, BACKEND_CONVENTION.md 3.1 규칙 2).

```json
{
  "success": true,
  "data": { "accepted": 5000, "dataVersion": "2026-09-01" },
  "timestamp": "2026-09-01T04:12:00+09:00",
  "requestId": "..."
}
```

**섞으면 호출하는 쪽이 불편해집니다.** 오류는 전역 예외 핸들러가 무조건 이 형식으로 내보내므로, 성공만 raw 면 Spark 배치가 두 형식을 모두 파싱해야 합니다.

그리고 **`requestId` 가 있어야 장애 추적이 됩니다.** 50번 호출 중 어느 요청이 실패했는지 nginx 로그와 대조하려면 필요합니다.

실패 응답 예시:

```json
{
  "success": false,
  "error": { "code": "INVALID_REQUEST", "message": "p50 ≤ p80 ≤ p95 위반", "fieldErrors": [] },
  "timestamp": "...",
  "requestId": "..."
}
```

### 3-2. `pipeline_config` 소유권 표기와 신선도 키

`activate` 가 `pipeline_config.baseline_active_version` 을 씁니다. 그런데 ERD 와 BACKEND_CONVENTION.md 에는 **"소유: 분산 담당"** 으로 되어 있습니다. 이제 **Spring 이 쓰는 테이블**이므로 백엔드가 표기를 고치겠습니다.

→ **반영 완료 (2026-09-02).** `baseline_serving`·`pipeline_config` 소유를 백엔드로 정정하고
`baseline_last_activated_at` 키를 DDL 주석에 명시했습니다. `detection_log` 만 분산 소유로 남습니다.

추가로, 계약에 **"마지막 활성화 시각"을 어디 저장할지가 없습니다.** `pipeline_config` 에 키를 하나 더 두는 것을 제안합니다.

```
baseline_active_version     = 2026-09-01
baseline_last_activated_at  = 2026-09-01T04:12:00
```

`DATA_STALE` 알림과 `confidence` 강등이 이 값을 읽습니다.

### 3-3. "절반 미만 급감" 임계값을 설정으로 빼주세요

> 행 수가 직전 활성 버전 대비 절반 미만으로 급감하지 않았는지 확인

의도는 맞지만 고정값 `0.5` 는 초기에 잘 안 맞습니다.

- 구간 수가 늘어나는 중이라 행 수가 계속 변합니다 → **오탐**
- 구간이 500 → 5000 으로 늘면 절반 기준이 지나치게 헐거워집니다 → **미탐**

`ddubi.baseline.min-row-ratio` 같은 설정값으로 빼고 기본 `0.5` 로 두겠습니다. 운영하면서 조정할 수 있어야 합니다.

---

## 4. 백엔드가 구현할 때 지킬 것

회의록 3번 규칙(검증 게이트 · 활성화 분리 · 삭제 규칙 · 신선도 기록 · 인증)은 그대로 따릅니다. 아래는 우리 쪽 구현 메모입니다.

- **네이티브 bulk upsert 사용.** JPA `saveAll()` 은 5000행에 5000 쿼리가 나가고 UNIQUE 충돌 시 갱신도 못 합니다. multi-row `INSERT ... ON DUPLICATE KEY UPDATE` 로 처리합니다
- **`/internal` 전용 SecurityFilterChain 분리.** 현재 체인은 JWT 기반입니다. `X-DDUBI-API-KEY` 로 인증하는 체인을 앞에 두고 **사용자 JWT 는 거부**합니다(BACKEND_CONVENTION.md 6.3)
- **`baselineType` 값 검증.** 지금은 `segment` 만 허용하고, 모르는 값은 400
- **`/internal` 은 `/api/v1` 아래가 아닙니다.** nginx 가 현재 `/api/` 만 백엔드로 넘기므로 `/internal/` location 이 따로 필요합니다 — 서버 이식 때 반영 부탁드립니다. 로컬 개발에서는 8080 직접 호출로 진행합니다

---

## 5. 문서 정정 — ODSay → 카카오맵

회의록 4번의 이 문장:

> route_request.raw_response 보관 정책: **ODSAY 응답 원본**은 경로당 수십 KB라...

**길찾기 제공자가 카카오맵으로 변경됐습니다** (`S15P21E202-21`, `be/dev` 머지 완료). `route_request.provider` 도 `ENUM('ODSAY','KAKAO') DEFAULT 'KAKAO'` 로 바뀌었습니다.

**지적 자체는 유효합니다.** 카카오 응답도 후보 15개 · 구간 38개라 작지 않습니다. `raw_response` 보관 정책은 백엔드가 Phase 3(경로·추천)에서 정하기로 한 미결 항목이며, 현재 유력안은 **"사용자가 선택한 경로만 저장"** 입니다. 보관 기간까지 함께 정하겠습니다.

---

## 5-1. `segment-status.schema.json` 검토 (2026-09-01 추가)

스키마 자체는 좋습니다. `status` enum 과 `causes` 배열이 있어 **알림 문구에 원인을 담을 수 있습니다**(`ROUTE_CHANGE` 알림에 "2호선 지연" 같은 이유를 넣는 데 씁니다).

`confidence` 는 **스키마를 바꿀 필요 없습니다.** 백엔드가 집계하겠습니다.

두 값은 층위가 다릅니다.

| | `segment-status.confidence` | `recommendation.confidence` |
| --- | --- | --- |
| 대상 | 구간 하나 | 추천 전체 |
| 값 | 0~1 실수 | HIGH / MID / LOW |

경로 하나에 구간이 10~20개고 각각 다른 값이 옵니다. 단순 임계값 표가 아니라 집계 규칙이 필요합니다.

### ⚠️ 도메인 규칙 8 정교화 필요 (백엔드 내부 안건)

> 규칙 8: 실시간 데이터가 없거나 stale 이면 baseline 으로 계산하고 `confidence=LOW`

**`WALK` 구간은 `segment_id` 가 항상 null 이라 실시간 데이터가 원천적으로 없습니다.** 거의 모든 경로가 도보로 시작해 도보로 끝나므로, 규칙을 문자 그대로 적용하면 **모든 추천이 LOW** 가 되어 등급이 무의미해집니다.

→ **실시간 대상 구간(BUS·SUBWAY)만 커버리지 판정에 넣는** 것으로 정교화합니다.

### 백엔드 집계 규칙 (Phase 3 구현 예정)

```
대상구간 = BUS·SUBWAY 만 (WALK 제외)
coverage = Σ(실시간 확보 구간 소요시간) / Σ(대상구간 소요시간)
avgConf  = Σ(구간 confidence × 소요시간) / Σ(실시간 확보 구간 소요시간)

HIGH : coverage ≥ 0.8 && avgConf ≥ 0.7
MID  : coverage ≥ 0.5
LOW  : 그 외
```

소요시간으로 가중하는 이유: 3분 환승 구간과 40분 지하철 구간의 데이터 결손은 무게가 다릅니다.

`status: "unknown"` 은 "데이터 없음"으로 취급합니다. **`blocked` 는 confidence 와 별개 축**으로 보고, 해당 후보를 순위에서 빼거나 큰 페널티를 줍니다 — 신뢰도 문제가 아니라 갈 수 없다는 뜻이기 때문입니다.

## 6. 다음 회의 안건에 대한 백엔드 의견

### 날씨 조회 — 안건이 이미 해소된 것으로 보입니다 (2026-09-01 정정)

`redis-keys.yaml` 을 다시 보니 `weather:latest:{nx}:{ny}` 값에 **`districts` 배열이 이미 들어 있습니다.**

```
{"districts":["종로구","중구"],"nx":60,"ny":127, "observations":{"T1H":27.3,...}, ...}
```

즉 **구별 키를 따로 만들 필요가 없습니다.** 격자 키로 읽으면 그 안에 구 목록이 함께 옵니다.

→ **백엔드가 좌표→격자 변환만 구현**하면 됩니다. 기상청 격자 변환은 좌표만 있으면 계산되는 공식이고, `schedule` 에 이미 `origin_lat/lng`, `dest_lat/lng` 이 있습니다.

Spark 가 구별 키를 한 번 더 쓰면 같은 데이터가 두 벌 생기고, 구 경계에 걸친 좌표를 어느 구로 볼지 또 정해야 합니다. **현재 구조가 낫습니다.**

### 비밀값 공유 채널

`INTERNAL_API_KEY` 가 추가되면서 공유해야 할 비밀값이 여러 개가 됐습니다.

```
MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD
REDIS_PASSWORD
JWT_SECRET
KAKAO_REST_API_KEY / KAKAO_CLIENT_SECRET
INTERNAL_API_KEY        ← 신규
```

전부 `.env` 로만 관리하고 커밋하지 않습니다. **전달 채널을 한 번 정해두는 게 좋겠습니다.**

### 좌표→segmentIds 변환 주체

`segment_id` 채번 규칙 자체가 아직 미확정입니다(안건 B-1). 백엔드가 정리한 안건 문서가 `docs/contracts/segment-id-agenda.md` 에 있으니, 이 논의와 함께 다루면 좋겠습니다. **기준선의 `segmentId` 도 같은 체계를 써야 성립합니다.**

---

## 7. 일정

**계약 확정은 지금 가능합니다.** `dow` 만 정해주시면 나머지는 백엔드가 문서에 반영하겠습니다. 인프라는 가짜 수신 서버로 배치 개발을 시작하셔도 됩니다.

**엔드포인트 구현은 Phase 5 예정**입니다. 회의록 3번 말씀대로 회원·일정·추천이 우선이고, 이 API 는 `transport` 수집기와 `segment_id` 설계가 끝나야 호출하는 쪽이 생깁니다.

현재 백엔드 진행 상황:

| Phase | 상태 |
| --- | --- |
| 0 골격 · 1 인증·회원 | 완료 |
| 2 장소·일정·반복 | 진행 예정 |
| 3 경로·추천 | |
| 4 알림·재계산 | |
| **5 internal API · 도착·리포트** | **이 업로드 API 포함** |
