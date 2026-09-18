# [백엔드 → 인프라] 배포 서버 backend 환경변수 누락

작성: 백엔드(서비스) 손정민 · 2026-09-04

---

## 먼저, 지적하신 내용이 맞습니다

**제가 틀렸습니다.** 프론트 안내 문서에 "서비스 계정 키를 서버에 넣어뒀다" 고 썼는데,
확인해 보니 **제 로컬 개발 환경(`backend/.env`)에만** 있었습니다. 배포 서버는 확인하지
않고 쓴 문장입니다. 프론트 문서도 정정했습니다.

확인해 주셔서 감사합니다. 그런데 **찾아보니 문제가 Firebase 하나가 아니었습니다.**

---

## 실제 상황 — backend 에 전달되는 환경변수가 절반입니다

`infra/docker-compose.yml` 의 `backend` 서비스가 넘기는 값과, backend 가
`application.yml` 에서 읽는 값을 대조했습니다.

| 환경변수 | 전달됨? | 없으면 생기는 일 |
| --- | --- | --- |
| `MYSQL_*` · `REDIS_*` · `JWT_SECRET` | ✅ | — |
| `KAKAO_REST_API_KEY` | ✅ | — |
| **`MAIL_HOST/PORT/USERNAME/PASSWORD`** | ❌ | 🔴 **로컬 회원가입이 불가능합니다** |
| **`GOOGLE_CLIENT_ID/SECRET`** | ❌ | 🔴 구글 로그인 실패 |
| **`NAVER_CLIENT_ID/SECRET`** | ❌ | 🔴 네이버 로그인 실패 |
| `KAKAO_APP_ID` · `KAKAO_CLIENT_SECRET` | ❌ | 🟡 카카오 일부 흐름 |
| **`FIREBASE_CREDENTIALS_BASE64/PATH`** | ❌ | 🟡 푸시 미발송 (보고해 주신 건) |

### 메일이 가장 급합니다

로컬 회원가입은 이메일 인증을 통과해야 진행됩니다(`AuthService:108` `requireVerified`).
`MAIL_USERNAME/PASSWORD` 가 없으면 `MailConfig` 가 **로그 발송기로 대체**해서, 인증코드가
서버 로그에만 남고 사용자에게는 영영 오지 않습니다.

**기동은 정상이고 API 도 200 을 주기 때문에** 겉으로는 멀쩡해 보입니다. 지금 배포 서버에서
회원가입을 시도하면 인증코드 입력 단계에서 막힙니다.

이게 Firebase 와 같은 구조의 문제라서, **키가 없으면 조용히 로그로 떨어지는 설계**가
배포에서는 오히려 문제를 늦게 발견하게 만들었습니다. 제 설계 판단이 CI 에서는 맞았지만
배포에서는 이런 부작용이 있다는 걸 이번에 알았습니다.

---

## `FCM_CREDENTIALS_PATH` 는 이름이 달라서 안 먹습니다

`infra/.env.example` 에 `FCM_CREDENTIALS_PATH` 가 있는데, **backend 가 읽는 이름은
`FIREBASE_CREDENTIALS_PATH`** 입니다. 채워 넣으셨어도 반영되지 않았을 값입니다.
혼동 방지를 위해 예시 파일에서 지우고 올바른 이름으로 바꿨습니다.

---

## 제가 한 것 (MR 로 올립니다)

1. `infra/docker-compose.yml` — `backend` 서비스에 누락된 환경변수 전부 추가
2. `infra/.env.example` — `FCM_CREDENTIALS_PATH` 제거, 올바른 이름들 추가 + 설명

전부 `${VAR:-}` 형태라 **값이 없어도 기동에는 영향이 없습니다.** 지금 배포에 바로 반영해도
깨지는 것이 없습니다.

---

## 부탁드릴 것

**compose 변경분을 반영해 주시고**, 서버1 `infra/.env` 에 값을 채울 수 있게 해 주세요.
**값은 제가 직접 넣겠습니다** — 키를 채팅이나 이슈에 올리지 않으려고 합니다.

서버1 `.env` 에 제가 접근할 방법(계정/경로)만 알려 주시거나, 아니면 GitLab CI/CD Variables
(Masked + Protected)로 주입하는 방식이면 제가 변수만 등록하겠습니다. **후자를 권합니다** —
서버가 늘어나도 한 곳에서 관리되고, 값이 로그에 찍히지 않습니다.

### FCM 은 파일이 아니라 base64 로 주시면 됩니다

볼륨 마운트가 필요 없어서 배포가 간단합니다. backend 가 두 방식을 모두 지원합니다.

```bash
base64 -w0 firebase-service-account.json
```

이 한 줄을 `FIREBASE_CREDENTIALS_BASE64` 에 넣으면 됩니다. 파일을 서버에 올리거나
compose 에 volume 을 추가할 필요가 없습니다.

---

## 반영 후 확인 방법

backend 로그에서 아래 세 줄이 **사라져야** 정상입니다.

```
MAIL_USERNAME/MAIL_PASSWORD 미설정 — 인증 메일을 실제로 보내지 않고 로그에만 남깁니다
FIREBASE_CREDENTIALS_PATH/BASE64 미설정 — 푸시를 실제로 보내지 않고 로그에만 남깁니다
```

FCM 이 제대로 붙으면 대신 이 줄이 뜹니다.

```
FCM 발송 준비 완료
```

`/actuator/health` 는 이 값들과 무관하게 UP 이라 **헬스체크로는 판별되지 않습니다.**
로그를 봐 주세요.

---

## 정정 2건 (2026-09-04, 확인 후)

### 1. backend 는 서버2 입니다

두 서버 다 확인해 주신 것으로 이해했습니다. 다만 인용해 주신 로그가 "서버1 backend" 였는데,
`.gitlab-ci.yml` 상 `backend` 는 `serving` 프로필이라 `deploy-server2` 로만 배포됩니다.
서버1 은 core·collect·spark·archive·hdfs·exporters 만 받습니다.

**서버1 에도 backend 컨테이너가 떠 있다면 제가 배포 구성을 잘못 이해한 것이니 알려 주세요.**
아니라면 이후 확인은 서버2 쪽을 보시면 됩니다.

### 2. GitLab CI/CD 변수만으로는 값이 서버에 닿지 않습니다

처음에 제가 "CI/CD Variables 에 넣으면 된다" 고 했는데 **틀렸습니다.** 배포 잡이
이렇게 생겼기 때문입니다.

```
ssh ubuntu@서버 "cd infra && docker compose up -d --build"
```

CI 변수는 러너 컨테이너 안에서만 존재합니다. `ssh` 로 넘어가는 순간 사라지고, 서버의
compose 는 **서버에 있는 파일**만 읽습니다.

---

## 그래서 이렇게 고쳤습니다 (이 MR)

**`deploy-server2` 잡이 CI 변수를 읽어 서버2에 `infra/.env.backend` 를 만듭니다.**
compose 의 backend 서비스가 그 파일을 `env_file` 로 읽습니다.

```yaml
# infra/docker-compose.yml
  backend:
    env_file:
      - path: .env.backend
        required: false      # 파일이 없어도 기동은 된다
```

### 이 방식을 고른 이유

- **공용 `.env` 를 건드리지 않습니다.** 비밀값이 다른 팀 설정과 섞이지 않고, 서버에서
  `.env` 를 편집하다 실수로 날릴 위험도 없습니다
- **`umask 077`** 로 만들어 소유자만 읽습니다
- **값을 ssh 인자로 넘기지 않습니다.** 인자로 주면 서버의 `ps` 에 잠깐이라도 그대로
  보입니다. 표준입력으로만 보냅니다
- **`required: false`** — 파일이 없어도 배포가 실패하지 않습니다. 그때는 지금처럼
  메일·푸시가 로그로만 남습니다

### 인프라팀이 하실 일 — 없습니다

`.gitlab-ci.yml` 이 인프라팀 파일이라 **검토만 부탁드립니다.** 머지되면 다음 배포부터
자동으로 적용됩니다.

### 값은 어디서 오나 — 둘로 나눴습니다

| 값 | 두는 곳 | 담당 |
| --- | --- | --- |
| `KAKAO_*` `GOOGLE_*` `NAVER_*` | 서버 `infra/.env` | PM (이미 전달·설정됨) |
| `MAIL_USERNAME` `MAIL_PASSWORD` `FIREBASE_CREDENTIALS_BASE64` | GitLab CI/CD 변수 | 백엔드(손정민) |

소셜 키는 이미 서버 `.env` 에 있으니 그대로 씁니다. compose 의 `environment:` 에
이름만 추가하면 컨테이너로 전달됩니다 — 지금까지는 그 줄이 없어서 값이 있어도
전달되지 않았습니다.

메일·FCM 만 CI 변수로 넣고 배포 잡이 `.env.backend` 로 심습니다.

> ⚠️ **한 값을 양쪽에 두면 안 됩니다.** compose 는 `environment:` 가 `env_file` 보다
> 우선이라, `.env` 에 값이 없으면 빈 문자열이 `.env.backend` 값을 덮어씁니다.
> 그래서 두 목록이 겹치지 않게 나눴습니다.

> ⚠️ 배포 기준 브랜치가 `develop` 이고 `Protected` 변수는 **보호 브랜치에만** 주입됩니다.
> `develop` 이 보호 브랜치로 설정돼 있는지 확인 부탁드립니다. 아니면 변수가 빈 값으로
> 들어가서 지금과 똑같은 증상이 반복됩니다.
