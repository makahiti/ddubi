#!/usr/bin/env bash
# 배포 직전 서버 준비 — 로컬 변경을 "지우지 않고" 옆으로 치운 뒤 최신 코드를 당겨온다.
#
# 왜 필요한가
#   운영 서버에서 마스터 자료를 다시 만들거나 손으로 고치면 추적 파일이 수정된 상태로 남는다.
#   그 상태에서 git pull --ff-only 는 "로컬 변경을 덮어쓰게 된다" 며 멈추고 배포가 실패한다.
#   2026-09-18 서버1이 하루 넘게 배포되지 못한 이유가 이것이었고, 실제 차이는 줄바꿈뿐이었다.
#
# 무엇을 하지 않는가
#   지우지 않는다. 되돌릴 수 있게 두 곳에 남긴다 — git stash 와 ~/deploy-backup/<시각>.
#   배포와 무관한 미추적 파일은 건드리지 않는다. 운영 자료가 거기 들어 있다
#   (예: backend/transit-master/reference-library, infra/nginx/html/.well-known).
#
# 되돌리는 법
#   git stash list            # "배포 자동 보관 …" 이름표를 찾는다
#   git stash apply stash@{N} # 추적 파일 변경을 되살린다
#   미추적 파일은 ~/deploy-backup/<시각>/ 아래 원래 경로 그대로 들어 있다.
#
# 쓰는 법: ssh 로 표준입력에 넣어 실행한다.
#   ssh <host> 'bash -s' -- "$CI_COMMIT_BRANCH" < infra/scripts/pre-deploy.sh
set -euo pipefail

branch="${1:?배포할 브랜치 이름이 필요합니다}"
repo="${DDUBI_REPO_DIR:-$HOME/S15P21E202}"
cd "$repo"

stamp="$(date +%Y%m%dT%H%M%S)"
backup="$HOME/deploy-backup/$stamp"

git fetch origin "$branch"
git checkout "$branch"

# ── 1. 추적 파일의 로컬 수정 ────────────────────────────────────────────────
# stash 만으로도 되살릴 수 있지만, stash 는 저장소 안에 있어서 저장소가 깨지면 같이 사라진다.
# 파일 사본을 따로 남겨 두 경로 중 하나만 살아도 복구되게 한다.
if ! git diff --quiet; then
    changed="$(git diff --name-only | wc -l)"
    mkdir -p "$backup"
    git diff --name-only -z | tar -czf "$backup/tracked-changes.tgz" --null -T -
    git stash push -m "배포 자동 보관 $stamp 브랜치=$branch 백업=$backup"
    echo "[pre-deploy] 수정된 추적 파일 ${changed}개를 보관했습니다 → stash + $backup"
else
    echo "[pre-deploy] 수정된 추적 파일 없음"
fi

# ── 2. 이번 배포가 새로 가져오는 파일과 이름이 겹치는 미추적 파일 ──────────
# 겹치는 것만 옮긴다. 전부 옮기면(git stash -u) 운영 자료 폴더까지 사라진다.
moved=0
while IFS= read -r path; do
    [ -n "$path" ] || continue
    [ -e "$path" ] || continue
    git ls-files --error-unmatch "$path" >/dev/null 2>&1 && continue   # 추적 파일은 1단계가 처리했다
    mkdir -p "$backup/$(dirname "$path")"
    mv "$path" "$backup/$path"
    moved=$((moved + 1))
    echo "[pre-deploy] 미추적 파일을 옮겼습니다: $path"
done < <(git diff --name-only HEAD "origin/$branch")

if [ "$moved" -gt 0 ]; then
    echo "[pre-deploy] 옮긴 미추적 파일 ${moved}개 → $backup"
fi

# ── 3. 최신 코드 ────────────────────────────────────────────────────────────
# --ff-only 를 유지한다. 서버에서 병합 커밋을 만들면 다음 배포부터 계속 꼬인다.
# 여기서 실패하면 서버 코드가 원격과 갈라진 것이니, 조용히 넘기지 말고 사람이 봐야 한다.
git pull --ff-only origin "$branch"
echo "[pre-deploy] 현재 코드: $(git log --oneline -1)"

# ── 4. 오래된 백업 정리 ─────────────────────────────────────────────────────
# 30일이 지난 것만 지운다. 배포마다 쌓이면 디스크를 먹고, 너무 빨리 지우면 되돌릴 수 없다.
find "$HOME/deploy-backup" -maxdepth 1 -mindepth 1 -type d -mtime +30 -exec rm -rf {} + 2>/dev/null || true
