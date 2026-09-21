# 잘못된 배포를 되돌려야 한다 (롤백)

서버는 항상 **develop 의 최신 커밋**을 배포한다. 배포 잡(deploy-server1/2)은 "그 잡이 속한 커밋"이 아니라 그 순간의 develop 을 `git pull` 하므로, 옛 파이프라인의 배포 버튼을 눌러도 옛 버전으로 돌아가지 않는다. 그래서 롤백도 develop 을 통해서 한다.

## 배포 잡이 코드를 받기 전에 하는 일 (2026-09-18 추가)

배포 잡은 `git pull` 앞에 `infra/scripts/pre-deploy.sh` 를 서버에서 돌린다. 서버에서 누가 추적 파일을 고쳐 두면 `git pull --ff-only` 가 "로컬 변경을 덮어쓴다" 며 멈추는데, 이 스크립트가 그 변경을 지우지 않고 `git stash` 와 `~/deploy-backup/<시각>/` 두 곳에 보관한 뒤 코드를 받는다. 미추적 파일은 이번 배포가 새로 가져오는 파일과 이름이 겹칠 때만 옮기고, 운영 자료 폴더(버스 참고 자료, .well-known)는 그대로 둔다.

배포 로그에 "수정된 추적 파일 N개를 보관했습니다" 가 보이거나 서버에서 `git stash list` 에 "배포 자동 보관" 항목이 있으면, 누군가 서버에서 파일을 직접 고쳤다는 뜻이다. 무엇을 왜 고쳤는지 확인하고, 필요한 변경이면 저장소에 커밋으로 올린 뒤 stash 를 정리한다(`git stash drop`). 점검 스크립트(`infra/scripts/checks/server1.sh`·`server2.sh`)의 "저장소" 항목이 이 stash 개수와 수정된 추적 파일 수를 WARN 으로 보여 준다.

## 먼저 판단할 것

| 질문 | 예 | 아니오 |
|---|---|---|
| 서비스가 지금 죽어 있나 (backend 502, 잡 재시작 반복) | 아래 "긴급 임시 조치" 먼저, 그다음 정식 롤백 | 정식 롤백만 |
| 문제 커밋이 DB 마이그레이션(V*.sql)을 포함하나 | **코드만 되돌리면 안 된다** — 아래 "마이그레이션이 얽힌 경우" | 코드 revert 로 충분 |
| 문제가 한 서버에만 있나 | 그 서버 배포 버튼만 | 둘 다 |

## 정식 롤백 — develop 에 되돌리는 커밋을 올리고 재배포

1. 문제 커밋(또는 머지 커밋)을 찾는다.
```
git fetch origin && git log --oneline -10 origin/develop
```
2. 되돌리는 커밋을 만든다. 머지 커밋이면 `-m 1` 이 필요하다.
```
git checkout -b hotfix/revert-<설명> origin/develop
git revert <커밋>              # 일반 커밋
git revert -m 1 <머지커밋>     # 머지 커밋: 첫 부모(develop 쪽)를 기준으로 되돌린다
git push origin hotfix/revert-<설명>
```
3. develop 으로 MR → 머지 (긴급이면 리뷰어에게 바로 알린다). MR 이 **Draft** 로 만들어지면 머지가 막히므로 "Mark as ready" 를 누른다.
4. develop 파이프라인에서 해당 서버의 **deploy 버튼**을 누른다.
5. 배포 뒤 확인: [backend-down-and-nginx-502.md](backend-down-and-nginx-502.md) 의 복구 확인 명령, 서버1 이면 스트리밍 batch 로그.

원래 변경을 다시 살릴 때는 revert 커밋을 다시 revert 하거나 고친 코드로 새 MR 을 올린다. 이력이 전부 남는다.

## 긴급 임시 조치 — 서버에서 특정 커밋으로 직접 되돌리기

MR·머지·배포를 기다릴 수 없을 때만. **다음 배포가 develop 최신으로 덮어쓰므로 임시다.**
```
cd ~/S15P21E202
git fetch origin
git log --oneline -5 origin/develop            # 돌아갈 커밋 확인 (직전 정상 커밋)
git checkout <정상커밋>
cd infra && $DC2 --profile data --profile serving --profile hdfs2 --profile monitoring up -d --build   # 서버2 예시. 서버1은 $DC1 과 해당 프로필
```
- 서버가 detached 상태가 되므로, 정식 롤백이 develop 에 머지된 뒤 `git checkout develop && git pull` 로 돌려 놓아야 다음 배포 잡이 실패하지 않는다.
- 어떤 커밋으로 돌렸는지, 언제 develop 으로 복귀시켰는지 검증 기록에 적는다.

## 마이그레이션이 얽힌 경우

Flyway 는 한 번 적용된 마이그레이션을 되돌리지 않는다. 코드만 이전 버전으로 되돌리면 "DB 에는 V5 가 적용됐는데 코드에는 V4 까지만 있다"는 상태가 되어 Flyway 가 기동을 거부할 수 있다(`Detected applied migration not resolved locally`).

| 경우 | 방법 |
|---|---|
| 새 마이그레이션이 컬럼·테이블 추가만 했고 옛 코드가 그것을 몰라도 동작한다 | 코드만 revert 하되 마이그레이션 파일은 남긴다 (revert 커밋에서 V*.sql 삭제를 되살린다). Flyway 이력과 파일이 일치하므로 기동된다 |
| 새 마이그레이션이 컬럼을 지우거나 바꿔서 옛 코드가 깨진다 | 되돌리는 마이그레이션(V6: 원상복구 SQL)을 **앞으로 추가**하는 방식으로 backend 팀이 만든다. 데이터가 없는 개발 DB 면 리셋도 선택지 |
| 개발 DB, 데이터 없음, 급함 | [backend-down-and-nginx-502.md](backend-down-and-nginx-502.md) A-2 의 DB 재초기화 |

배포 전에 "이 MR 에 마이그레이션이 있나"를 확인하는 습관이 롤백을 단순하게 만든다.

## 설정(.env)만 잘못된 경우

`.env` 는 깃에 없으므로 서버에서 직접 고치고 해당 컨테이너만 다시 올린다. 백업(`.env.bak-<날짜>`)을 먼저 남긴다.
```
cp -p .env .env.bak-$(date +%Y%m%d) && vi .env
$DC1 --profile spark up -d spark-streaming-weather      # 바뀐 값을 쓰는 컨테이너만
```

## 롤백 뒤 확인

- 서버의 `git log -1` 이 의도한 커밋인가 (임시 조치면 detached, 정식이면 develop 최신)
- 재생성된 컨테이너가 Up 이고 로그에 오류가 없는가
- backend 면 `/api` 401, 스트리밍이면 batch 로그, nginx 502 면 리로드

## 연습 기록

| 날짜 | 한 일 | 소요 | 결과 | 절차서 수정 |
|---|---|---|---|---|
| 2026-09-07 | 6: nginx 안내 페이지에 무해한 HTML 주석을 넣어 배포(푸시 14:25:49 → 머지 14:26:49 → 서버 반영 14:29:33) → `git revert` 커밋 푸시 14:33:16 → 머지 14:35:29 → 서버 반영 14:36:15. 서빙되는 페이지에서 표식이 사라짐 확인. 컨테이너 재생성 없음, `/api` 401 유지 | **롤백 2분 59초** (푸시→서버 반영, 사람 개입 포함) | 유실·중단 없음 | MR 이 Draft 로 생성되면 머지가 막힌다 — "Mark as ready" 필요 (GitLab 설정 확인 항목) |
