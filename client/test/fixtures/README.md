# FE schedule-evaluation-v2 fixtures

이 폴더의 JSON은 BE/ML 실제 응답이 확정되기 전 FE 회귀 테스트에 사용하는 합성 예제다. 운영 결과나 실제 사용자 데이터가 아니며 request ID는 삭제·치환했다.

## 성공 및 상태 예제

- 결정: `keep`, `advance`, `margin`, `late`, `data`, `search`
- 실행 상태: `pending`, `running`, `failed`, `stopped`, `departure_required`, `stale`, `departed`
- 검증 gate: `preview`, `departed`
- 호환성: `unknown`, `legacy`
- 도보 품질: `keep`은 BOTH, `search`는 부분 coverage, `walking_fallback`은 endpoint 미검증 fallback

## 오류 예제

- `error_409`: 오래된 evaluation 수락
- `error_422`: P/D 입력 검증 실패
- `error_503`: 교통 제공자 장애

## 비밀값을 제거한 요청 예제

```json
{
  "origin": {"longitude": 127.0, "latitude": 37.5},
  "destination": {"longitude": 127.03, "latitude": 37.51},
  "plannedDepartAt": "2026-09-18T08:10:00+09:00",
  "arriveBy": "2026-09-18T09:00:00+09:00"
}
```

수락 요청에는 다음 값 외에 출발시각이나 route ID를 넣지 않는다.

```json
{"evaluationId": "eval-1"}
```

## 호환 처리

- 모르는 state, decision, legRole, durationSource는 원문을 보존하고 수락을 차단한다.
- v1 응답은 검증용·비운영 결과로 변환한다.
- nullable ETA, route, proposed departure는 누락으로 단정하지 않고 화면에 `확인 불가`로 표시한다.
- 실제 BE/ML fixture를 받으면 이 합성 예제와 필드·enum·nullable 차이를 먼저 비교한다.

## 화면 캡처

`flutter test --update-goldens test/generate_schedule_evaluation_captures.dart`를 실행하면 `test/captures/`에 상태별 합성 화면이 생성된다. 이 이미지는 실제 서버 연동 증거가 아니라 FE fixture 표시 결과다.
