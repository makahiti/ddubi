# v12 버스 모델 로컬 예측 서비스

Jira [S15P21E202-46](https://ssafy.atlassian.net/browse/S15P21E202-46).
GPU 서버의 최종 LightGBM 모델을 **CPU로 실행해 실제 구간 예측값을 확인**한다.
학습 서버와 동일한 전처리·ID 인코더·결측 규칙을 사용한다. 모델의 운영 승인 상태는 false다.
추천 API 적용은 별도의 ID 조합 검증·미래 평가·운영 승인 이후에 연결한다.

## 설치

Python 3.12 기준. 프로젝트 루트에서 실행한다.

```powershell
python -m venv ml-serving/.venv
ml-serving/.venv/Scripts/python.exe -m pip install -r ml-serving/requirements-dev.txt
ml-serving/.venv/Scripts/python.exe ml-serving/tools/install_bundle.py --archive data/ml_staging/serving-reference-20260910.zip --model data/ml_staging/selected_model.joblib --destination artifacts/ml-serving/20260908-v12
```

모델과 참조 데이터는 Git 제외 디렉터리 `artifacts/ml-serving/`에 둔다.
기존 파일은 덮어쓰지 않는다. 다른 날짜의 묶음은 새 디렉터리로 설치하고 검증한 후
`ML_BUNDLE_DIR`을 그 경로로 설정한다. 모델·코드·참조 파일을 실행 중 교체하지 않는다.
`serving-inspection.zip`은 조사용 소스이며 참조 데이터 ZIP을 대신하지 못한다.

## 실제 결과 확인

```powershell
ml-serving/.venv/Scripts/python.exe ml-serving/tools/verify_bundle.py --bundle artifacts/ml-serving/20260908-v12 --golden ml-serving/examples/v12-server-golden.json --output data/ml-serving-verification
ml-serving/.venv/Scripts/python.exe -m uvicorn app:app --app-dir ml-serving --host 127.0.0.1 --port 8000
```

브라우저에서 `http://127.0.0.1:8000/docs`를 열고 배치 API에
`data/ml-serving-verification/request.json`의 내용을 넣으면 실제 결과를 볼 수 있다.

```powershell
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8000/v1/bus/predictions:batch' -ContentType 'application/json' -InFile data/ml-serving-verification/request.json
```

결과 파일은 `response.json`, 서버 원본 대비 차이와 응답시간은 `verification.json`이다.
`examples/v12-server-golden.json`은 2026-09-10 GPU 서버 원본 추론으로 만든 10개 등록 ID의
실제 결과다. 최초 다운로드 ZIP의 표본은 모두 미등록 노선이어서, 위 명령은 서버에서
추가로 검증한 이 파일을 명시한다. 새 exporter는 등록 ID 표본을 선택한다.
숫자는 **인접 버스 구간의 시간대별 평균 소요시간(초)**이며 도보·대기·환승·전체 경로 시간이 아니다.
`PREVIEW`는 실제 모델 계산 결과지만 운영 추천 사용 승인을 뜻하지 않는다.
결측 수와 과거 관측 표본 수를 함께 확인한다.

## 서버에서 새 날짜 묶음 생성

`tools/export_v12.py`를 자신의 `j-moo/experiments/runtime/`에 두고 실행한다.
기존 학습·원천 파일을 수정하거나 GPU 학습을 다시 시작하지 않는다.

```bash
python export_v12.py --root /home/j-j15e202 --start 2026-09-10 --end 2026-09-17 --output /home/j-j15e202/j-moo/experiments/runs/20260908-v12/models/serving-reference-20260910.zip
```

1~31일 범위에 필요한 과거 원천만 복사한다. 원본에서 없던 파일은 manifest에 남긴다.
동일 이름의 ZIP은 덮어쓰지 않는다. 모델 파일 `selected_model.joblib`은 별도로 내려받는다.
`portable_features.py`는 학습 원문의 날짜·이력·인코더 함수만 추출한 것으로
학습 스크립트 import, 서버 고정 경로, 학습 실행을 포함하지 않는다.

## 테스트

```powershell
ml-serving/.venv/Scripts/python.exe -m pytest ml-serving/tests -q
```

실제 묶음이 없으면 실모델 대조 테스트만 skip된다. 이를 실모델 검증 통과로 보고하지 않는다.
Windows sandbox의 임시 폴더 접근 문제가 있으면 프로젝트 `data/` 아래 새로운 테스트 임시
디렉터리를 `--basetemp`로 지정한다. 기존 사용자 데이터 경로를 지정하지 않는다.

## 운영 전 남은 조건

- 서울 provider ID와 노선·인접 정류장·순번 조합의 학습/마스터 대조
- 참조 자료 가용시각·갱신과 미래 기간 평가
- 실제 입력 분포의 결측 및 노선별 커버리지 측정
- 최대 배치·동시 부하·응답시간/메모리와 Backend timeout 합의
- 승인된 `BusPredictionPort` HTTP 어댑터 및 내부 배포 주소 확정

현재 단계는 로컬 수치 확인이다. Redis 보정·기준선 적재·Spring 추천 정책을 변경하지 않는다.
HTTP 계약: `docs/contracts/ml-serving-http.md`.

## 백엔드 전달 자료

[상세 HTTP 명세](../docs/contracts/ml-serving-http.md)에 필드별 타입·범위·nullable,
Backend 매핑 대응표, 오류 JSON, 동시 호출/timeout과 기존 포트의 차이를 정리했다.
아래 파일은 실행 코드에서 생성해 검증한 예시이며 모델 설치 후 동일 입력으로 호출할 수 있다.

- [정상 일괄 요청](examples/bus-batch-request.json) / [실제 응답](examples/bus-batch-response.json)
- [미지원 출처 포함 요청](examples/bus-batch-partial-request.json) / [부분 실패 응답](examples/bus-batch-partial-response.json)
- [422 오류 응답](examples/bus-batch-invalid-response.json) / [readiness 응답](examples/bus-health-ready.json)

정상 예시는 독립적인 두 검증 edge이며 연속된 사용자 경로를 의미하지 않는다.
기존 `BusPredictionPort`의 운영 승인 조건을 충족한 응답으로 해석하지 않는다.
