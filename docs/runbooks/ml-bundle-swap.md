# ml-serving 모델 묶음을 새 버전으로 바꿔야 한다

> 상황: 데이터팀(종무)이 새 모델·참고 자료 묶음(zip + SHA256SUMS.txt + 안내)을 보냈다. 참고 자료는 날짜 범위가 있어 주기적으로 갈아야 한다(v12 는 9/17 → 9/30 으로 연장).
> 검증: **검증됨(2026-09-15)**. 인프라 담당이 첫 교체를 이 순서로 직접 했고, 재생성 40초 뒤 healthy 와 `supported_until` 변경을 확인했다.

## 0. 구조

- 서버2 호스트 `~/ddubi-models/<버전>/` 에 묶음을 버전별 폴더로 둔다. 컨테이너 안에서는 항상 `/models/v12` 로 보인다.
- 어느 폴더를 붙일지는 서버2 `infra/.env` 의 `ML_BUNDLE_DIR` 이 정한다. 이 값은 git 에 없다.
- 마운트 경로는 컨테이너를 만들 때 정해지므로 `.env` 를 바꾼 뒤 **ml-serving 만 다시 만들어야** 한다. `docker restart` 로는 반영되지 않는다.
- ml-serving 이 잠깐 없어도 backend 는 카카오 값으로 대체 동작한다. 사용자 API 는 끊기지 않는다.
- 옛 폴더는 지우지 않는다. 문제가 생기면 `.env` 한 줄을 되돌려 1분 안에 복구한다.

## 1. 받은 파일 올리기 (서버2)

```bash
mkdir -p ~/ddubi-model-upload/<날짜>
# PC 에서: scp -i ~/.ssh/J15E202T.pem <zip> SHA256SUMS.txt <안내문> ubuntu@j15e202.p.ssafy.io:~/ddubi-model-upload/<날짜>/
```

## 2. 파일 지문 확인

```bash
cd ~/ddubi-model-upload/<날짜>
sha256sum -c SHA256SUMS.txt          # 파일마다 ": OK". FAILED 면 다시 받는다
```

## 3. 새 폴더에 설치 (저장소의 설치 도구, 호스트 python3 3.11 이상)

```bash
python3 ~/S15P21E202/ml-serving/tools/install_bundle.py \
  --archive ./<zip 파일명> \
  --model ~/ddubi-models/<기존 버전>/selected_model.joblib \
  --destination ~/ddubi-models/<새 버전 폴더>
```
- 대상 폴더는 미리 만들지 않는다. 도구가 만들고, 모델을 복사해 넣고, `bundle.json` 의 파일별 지문과 대조한다.
- 기대 출력: `{"installed": ".../<새 버전 폴더>", "verified_files": N}`.
- 모델 파일 자체가 바뀐 묶음이면 `--model` 에 새 모델 경로를 준다(안내문에 적혀 있다).

## 4. .env 경로 전환

```bash
cd ~/S15P21E202/infra
cp .env .env.bak-<날짜>-mlbundle
sed -i 's#^ML_BUNDLE_DIR=.*#ML_BUNDLE_DIR=/home/ubuntu/ddubi-models/<새 버전 폴더>#' .env
grep ^ML_BUNDLE_DIR= .env
```
편집기로 고쳐도 된다. 절대 경로로 쓰고, 줄 끝이 LF 인지 확인한다.

## 5. ml-serving 만 다시 만들기

```bash
docker compose -f docker-compose.yml -f docker-compose.server2.yml --profile serving up -d --no-deps --force-recreate ml-serving
```
`--no-deps` 가 backend 등 다른 서비스를 건드리지 않게 한다.

## 6. 확인

```bash
docker exec nginx curl -s http://ml-serving:8000/health/ready | python3 -m json.tool | grep -E 'status|supported|production'
docker ps --filter name=ml-serving --format '{{.Status}}'     # 60초 안에 healthy
docker inspect ml-serving --format '{{range .Mounts}}{{.Source}}{{println}}{{end}}' | grep ddubi-models
```
기대: `READY_FOR_PREVIEW`, `supported_until` 이 새 날짜, healthy. Grafana 의 "ml-serving 다운" 알림은 5분 넘게 실패해야 뜨므로 정상 교체에서는 울리지 않는다.

## 7. 되돌리기 (5분 안에 안 되면)

```bash
cd ~/S15P21E202/infra
cp .env.bak-<날짜>-mlbundle .env
docker compose -f docker-compose.yml -f docker-compose.server2.yml --profile serving up -d --no-deps --force-recreate ml-serving
```

## 8. 실패 경로

| 증상 | 원인 | 조치 |
|---|---|---|
| `sha256sum -c` 에 FAILED | 전송 중 파일 손상 | 그 파일만 다시 받는다. 설치로 넘어가지 않는다 |
| 설치 도구가 "destination exists" | 폴더를 미리 만들었거나 같은 이름 재사용 | 그 폴더를 지우고 다시. 기존 버전 폴더는 건드리지 않는다 |
| 설치 도구가 SHA 불일치 | zip 안 파일과 bundle.json 이 다름 | 데이터팀에 알린다. 설치하지 않는다 |
| `compose up` 이 "no such file: docker-compose.yml" | 업로드 폴더에서 실행함 | `cd ~/S15P21E202/infra` 뒤 다시 |
| ready 가 503 `MODEL_NOT_READY` | 모델 로드 실패 | `docker logs ml-serving --tail 30`. 파일 누락·라이브러리 버전 불일치(bundle.json 의 runtime_versions 와 Dockerfile) |
| healthy 인데 `supported_until` 이 옛 날짜 | `.env` 가 안 바뀜 또는 restart 만 함 | `grep ML_BUNDLE_DIR .env`, 5절의 `--force-recreate` 다시 |
