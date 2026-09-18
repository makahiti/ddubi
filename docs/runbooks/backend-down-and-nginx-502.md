# backend 가 죽었다 / 배포 뒤 API 가 502 다

대상: 서버2의 `backend`(Spring) 와 `nginx`.

## 증상

- https://j15e202.p.ssafy.io/api/... 가 **502** 또는 응답 없음. 정상이면 인증 없이 호출했을 때 **401** 이 온다
- https://j15e202.p.ssafy.io/healthz 는 200 — 이것은 nginx 자신의 응답이라 backend 상태를 말해 주지 않는다
- 파이프라인(수집·Spark·아카이버)은 전부 정상으로 보인다. backend 와 파이프라인은 저장소로만 연결되어 서로 독립이다

## 먼저 확인 (서버2)

```
docker ps -a --format "{{.Names}}\t{{.Status}}" | grep -E "backend|nginx|mysql|redis"
docker inspect backend --format "restarts={{.RestartCount}} health={{.State.Health.Status}} exit={{.State.ExitCode}}"
docker logs backend --tail 200 2>&1 | grep -E "ERROR|Caused by|Started .*Application" | tail -8
docker exec backend curl -s localhost:8080/actuator/health        # 컨테이너 안에서 직접 — nginx 를 거치지 않는다
```

읽는 법:

| 보이는 것 | 원인 | 조치 |
|---|---|---|
| backend 가 1분마다 Restarting, 로그에 `FlywayValidateException` / `checksum mismatch` | 이미 적용된 DB 마이그레이션 파일이 수정된 채 배포됐다 | 아래 A |
| backend 는 healthy 이고 actuator 도 UP 인데 밖에서 502 | nginx 가 옛 backend IP 를 기억하고 있다 (backend 재생성 뒤) | 아래 B |
| `Communications link failure` / MySQL 연결 오류 | MySQL 이 죽었거나 아직 안 떴다 | `docker logs mysql --tail 30`, 재시작 정책 대기 |
| `JWT_SECRET` 관련 오류로 기동 거부 | 서버 `.env` 에 값이 없다 | 인프라가 `.env` 확인 |
| `MAIL_USERNAME ... 미설정`, `FIREBASE ... 미설정` 경고 (WARN) | 비밀값이 전달되지 않았다. 기동은 되고 메일·푸시만 로그로 대체됨 | GitLab CI/CD 변수 확인 후 deploy-server2 재실행 |

## 조치

**A. Flyway 검증 실패** — backend 코드 문제이므로 backend 팀과 함께 결정한다.
1. 정석: 수정된 마이그레이션 파일을 적용된 내용으로 되돌리고 변경분을 다음 번호(V2…)로 추가 → develop 머지 → deploy-server2
2. 개발 초기 예외: DB 를 비우고 다시 만든다. **데이터가 없음을 확인하고 덤프를 남긴 뒤에만.**
```
P=$(grep -E "^MYSQL_ROOT_PASSWORD=" .env | cut -d= -f2-)
docker exec -e MYSQL_PWD="$P" mysql mysql -uroot -N -e "SELECT COALESCE(SUM(table_rows),0) FROM information_schema.tables WHERE table_schema='ddubi' AND table_name<>'flyway_schema_history'"
docker exec -e MYSQL_PWD="$P" mysql mysqldump -uroot --single-transaction ddubi > ~/mysql-ddubi-before-reinit-$(date +%Y%m%d).sql
$DC2 --profile serving --profile data stop backend mysql
$DC2 --profile data rm -f mysql && docker volume rm ddubi_mysql-data
$DC2 --profile data --profile serving up -d mysql backend
```
3. 2 를 한 뒤에는 반드시 B 도 확인한다 (backend 가 새로 만들어졌으므로 IP 가 바뀐다).

**B. nginx 가 옛 IP 를 기억** — 2026-09-07 이후 nginx 템플릿은 `resolver 127.0.0.11 valid=10s` 와 변수 upstream 을 써서 backend 주소를 10초마다 다시 찾는다. 그래서 backend 재생성 뒤 502 는 **최대 10초 안에 스스로 풀린다**.
10초가 지나도 502 가 계속되면 nginx 가 옛 템플릿으로 떠 있는 것이다. 배포 잡은 끝에 `scripts/post-deploy.sh server2` 로 템플릿을 다시 적용하고 reload 한 뒤 `/api` 가 401 인지 확인하므로, 이 경우는 배포 잡이 실패로 표시된다. 손으로 같은 일을 하려면 아래 명령을 쓴다.
```
cd ~/S15P21E202/infra && bash scripts/post-deploy.sh server2      # 템플릿 재적용 + reload + backend·/api 확인
curl -s -o /dev/null -w "%{http_code}\n" https://j15e202.p.ssafy.io/api/health    # 401 이면 해소
```

**C. 단순히 죽었다면** 재시작 정책이 다시 띄운다. 30초 뒤에도 Up 이 아니면 `$DC2 --profile serving up -d backend`.

## 복구 확인

```
curl -s -o /dev/null -w "api=%{http_code}\n" https://j15e202.p.ssafy.io/api/health   # 401
docker exec backend curl -s localhost:8080/actuator/health                            # {"status":"UP",...}
docker logs backend 2>&1 | grep -E "Started .*Application in" | tail -1
docker logs backend 2>&1 | grep -c "미설정"                                             # 비밀값이 다 들어갔으면 0
```

## 예방·후속

- 배포 후 확인은 항상 `/api` 응답 코드(401)로 한다. `/healthz` 로 확인하면 backend 다운을 놓친다 (2026-09-03 에 70분 동안 몰랐던 이유).
- 배포된 마이그레이션은 수정하지 않는다는 규칙을 backend 팀과 공유했다 (infra-guide 4절).
- nginx IP 캐시는 2026-09-07 에 `resolver 127.0.0.11 valid=10s` + 변수 upstream 으로 해결했다. nginx 템플릿(`infra/nginx/templates/`)은 마운트라 git pull 만으로는 반영되지 않고 **컨테이너 재시작**이 필요하다.
- backend 헬스를 Prometheus 가 감시하게 하면 이 상황이 알림으로 온다 (모니터링 확장 항목).

## 연습 기록

| 날짜 | 한 일 | 복구까지 | 유실 | 절차서 수정 |
|---|---|---|---|---|
| 2026-09-03 | Flyway 검증 실패로 77회 재시작 (실제 장애). A-2 로 복구, 이어서 B 발생·해소 | 발견 후 15분 (미발견 70분) | 없음 (개발 DB, 0행) | 이 절차서의 출발점 |
| 2026-09-07 | 2-a: 컨테이너 안 자바(PID 1)에 SIGTERM → 정상 종료(143) → 재시작 정책이 즉시 재기동 → 21초 뒤 healthy, `/api` 401. SIGKILL 은 PID 1 이 무시해 효과 없음 | 자동, 21초 | 없음 | 시뮬레이션은 `docker exec backend kill -TERM 1` |
| 2026-09-07 | 2-b: backend 를 다른 IP 로 재생성(옛 IP 를 임시 컨테이너가 점유) → `/api` 502 재현(nginx 오류 5건) → `nginx -s reload` → 1초 뒤 401 | 리로드 후 1초 | 없음 | B 절차 확정 |
