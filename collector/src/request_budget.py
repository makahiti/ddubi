"""공유 SQLite에 실제 HTTP 시도를 기록한다. 비밀키는 SHA-256만 저장한다."""
from __future__ import annotations

import hashlib
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import unquote


class BudgetExceeded(RuntimeError):
    pass


class RetryWait(RuntimeError):
    def __init__(self, retry_at, now, message="retry pending"):
        self.retry_at = retry_at
        self.wait_seconds = max(1, retry_at - now)
        super().__init__(message)


class RetryScheduled(RetryWait):
    pass


class RequestBudget:
    # 여러 Collector 컨테이너가 같은 Docker volume의 DB를 공유한다. WAL은 읽기와
    # 쓰기의 충돌을 줄이고 busy_timeout은 다른 프로세스의 짧은 쓰기가 끝날 때까지
    # 즉시 실패하지 않고 기다리게 한다.
    BUSY_TIMEOUT_MS = 30_000

    def __init__(self, path: str, key: str, limit: int = 900, clock=time.time):
        if not 1 <= limit <= 1000:
            raise ValueError("API_DAILY_LIMIT must be between 1 and 1000")
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.key = hashlib.sha256(unquote(key.strip()).encode()).hexdigest()
        self.limit, self.clock = limit, clock
        with self._db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS requests (key TEXT, at REAL)")
            db.execute("CREATE INDEX IF NOT EXISTS requests_key_at ON requests(key, at)")
            db.execute("CREATE TABLE IF NOT EXISTS cycles (key TEXT, source TEXT, next REAL, PRIMARY KEY(key, source))")
            db.execute("CREATE TABLE IF NOT EXISTS cycle_retries (key TEXT, source TEXT, retry_at REAL, used INTEGER, PRIMARY KEY(key, source))")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(
            self.path,
            timeout=self.BUSY_TIMEOUT_MS / 1000,
            isolation_level=None,
        )
        try:
            db.execute(f"PRAGMA busy_timeout={self.BUSY_TIMEOUT_MS}")
            # journal_mode 전환은 busy_timeout을 적용하기 전에도 locked를 반환할 수
            # 있다. 이미 WAL이면 읽기만 하고, 최초 전환을 다른 프로세스가 진행
            # 중이면 제한 시간 동안 재시도한다.
            deadline = time.monotonic() + self.BUSY_TIMEOUT_MS / 1000
            while True:
                try:
                    journal_mode = db.execute("PRAGMA journal_mode").fetchone()[0]
                    if str(journal_mode).lower() != "wal":
                        journal_mode = db.execute("PRAGMA journal_mode=WAL").fetchone()[0]
                    if str(journal_mode).lower() != "wal":
                        raise RuntimeError("request budget SQLite WAL mode is unavailable")
                    break
                except sqlite3.OperationalError as error:
                    if "locked" not in str(error).lower() or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.01)
            db.execute("PRAGMA synchronous=NORMAL")
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.execute("COMMIT")
        except BaseException:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def claim_cycle(self, source: str, interval: int, expected_requests: int, *, group: str | None = None, allow_retry: bool = False) -> bool:
        """재시작·동시 프로세스가 같은 소스를 연속 수집하지 않도록 선점한다."""
        now = self.clock()
        with self._db() as db:
            row = db.execute("SELECT next FROM cycles WHERE key=? AND source=?", (self.key, source)).fetchone()
            retry = db.execute("SELECT retry_at FROM cycle_retries WHERE key=? AND source=?", (self.key, source)).fetchone() if allow_retry else None
            retry_due = bool(row and row[0] > now and retry and retry[0] is not None)
            if row and row[0] > now:
                if not retry_due:
                    return False
                if retry[0] > now:
                    raise RetryWait(retry[0], now)
            if group:
                group_row = db.execute("SELECT next FROM cycles WHERE key=? AND source=?", (self.key, group)).fetchone()
                if group_row and group_row[0] > now:
                    return False
            used = db.execute("SELECT count(*) FROM requests WHERE key=? AND at>?", (self.key, now-86400)).fetchone()[0]
            if used + expected_requests > self.limit:
                raise BudgetExceeded("Not enough rolling-24h request budget for this cycle")
            if retry_due:
                # Consume the one retry atomically; preserve the regular cycle deadline.
                db.execute("UPDATE cycle_retries SET retry_at=NULL WHERE key=? AND source=?", (self.key, source))
            else:
                db.execute("INSERT OR REPLACE INTO cycles VALUES (?, ?, ?)", (self.key, source, now+interval))
                db.execute("DELETE FROM cycle_retries WHERE key=? AND source=?", (self.key, source))
                if allow_retry:
                    db.execute("INSERT INTO cycle_retries VALUES (?, ?, NULL, 0)", (self.key, source))
            if group:
                db.execute("INSERT OR REPLACE INTO cycles VALUES (?, ?, ?)", (self.key, group, now+86400))
        return True

    def schedule_retry(self, source, delay_seconds, expected_requests):
        """Persist one retry within a regular cycle, only when whole-cycle budget fits."""
        now = self.clock()
        with self._db() as db:
            cycle = db.execute("SELECT next FROM cycles WHERE key=? AND source=?", (self.key, source)).fetchone()
            retry = db.execute("SELECT used FROM cycle_retries WHERE key=? AND source=?", (self.key, source)).fetchone()
            if not cycle or not retry or retry[0] or now + delay_seconds >= cycle[0]:
                return None
            used = db.execute("SELECT count(*) FROM requests WHERE key=? AND at>?", (self.key, now-86400)).fetchone()[0]
            if used + expected_requests > self.limit:
                return None
            at = now + delay_seconds
            db.execute("UPDATE cycle_retries SET retry_at=?, used=1 WHERE key=? AND source=?", (at, self.key, source))
            return at

    def next_cycle_at(self, source):
        with self._db() as db:
            row = db.execute("SELECT next FROM cycles WHERE key=? AND source=?", (self.key, source)).fetchone()
            return row[0] if row else None

    def before_request(self, request) -> None:
        """HTTP 요청 직전에 커밋. 실패/리다이렉트도 차감하며 환불하지 않는다."""
        now = self.clock()
        with self._db() as db:
            db.execute("DELETE FROM requests WHERE at<=?", (now-86400,))
            used = db.execute("SELECT count(*) FROM requests WHERE key=?", (self.key,)).fetchone()[0]
            if used >= self.limit:
                raise BudgetExceeded("Rolling-24h API request limit reached")
            db.execute("INSERT INTO requests VALUES (?, ?)", (self.key, now))
