# 버스 전처리 결과를 보는 방법

이 폴더의 입력과 결과는 **직접 만든 가상 데이터**입니다. 실제 버스 운행 표본이 아닙니다.

- `bus-silver-input.jsonl`: 테스트 입력 8건
- `bus-silver-review/position.csv`: 정제된 위치 2건
- `bus-silver-review/arrival.csv`: 정제된 도착예정 2건
- `bus-silver-review/rejected.csv`: 필수 차량 ID 누락·깨진 JSON 2건
- `bus-silver-review/duplicates.jsonl`: 동일 이벤트 반복 1건
- `bus-silver-review/quality.json`: 건수와 결측·오류 사유

JSONL 파일은 숫자·문자열·null 타입을 보존합니다. CSV의 `null`은 사람이 확인하기 위한 표시입니다.
노선 ID `00124`는 문자열이므로 앞의 0을 유지해야 합니다. Excel 자동 변환 결과는 타입 검증 기준이 아닙니다.

위치 첫 행은 좌표가 유효하지만 원본 관측시각은 없어서 null입니다. 수신시각을 실제 관측시각으로 채우지 않았습니다.
위치 둘째 행은 경도가 `NaN`이라 좌표 쌍을 null로 만들고 이유를 남겼습니다.
도착 첫 행의 180·540은 초이고 `traTime1=3.5`는 별도 분 컬럼입니다.
도착 둘째 행의 첫 버스 0초는 보존했고, 두 번째 -1초는 유효하지 않아 null로 만들었습니다.

실행 방법과 전체 컬럼·결측 처리 이유는 `docs/contracts/bus-silver.md`에 있습니다.
