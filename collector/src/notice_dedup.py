"""TOPIS publication ledger; payloads are compared, never modified."""

import hashlib
import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path


class NoticeDedup:
    def __init__(self, path, topic, clock=time.time):
        self.path = str(path)
        self.topic = topic
        self.clock = clock
        self.pending = {}
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute(
                "CREATE TABLE IF NOT EXISTS topis_publications (topic TEXT, notice_id TEXT, content_hash TEXT NOT NULL, last_seen REAL NOT NULL, last_published REAL NOT NULL, PRIMARY KEY(topic, notice_id))"
            )

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            with db:
                yield db
        finally:
            db.close()

    def reserve(self, record):
        raw = record.payload["raw"]
        # Hash only provider content, not the collection time / generated envelope ID.
        digest = hashlib.sha256(
            json.dumps(raw, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
        ).hexdigest()
        identifier = str(record.payload.get("providerNoticeId") or "").strip()
        missing_id = not identifier
        identifier = "id:" + identifier if identifier else "content:" + digest
        if identifier in self.pending:
            return None, "pending", missing_id
        with self.connect() as db:
            previous = db.execute(
                "SELECT content_hash FROM topis_publications WHERE topic=? AND notice_id=?", (self.topic, identifier)
            ).fetchone()
            if previous and previous[0] == digest:
                db.execute(
                    "UPDATE topis_publications SET last_seen=? WHERE topic=? AND notice_id=?",
                    (self.clock(), self.topic, identifier),
                )
                return None, "unchanged", missing_id
        self.pending[identifier] = digest
        return (identifier, digest), "changed" if previous else "new", missing_id

    def delivered(self, token, success):
        identifier, digest = token
        try:
            if success:
                now = self.clock()
                with self.connect() as db:
                    db.execute(
                        "INSERT INTO topis_publications VALUES (?, ?, ?, ?, ?) ON CONFLICT(topic, notice_id) DO UPDATE SET content_hash=excluded.content_hash, last_seen=excluded.last_seen, last_published=excluded.last_published",
                        (self.topic, identifier, digest, now, now),
                    )
        finally:
            self.pending.pop(identifier, None)


def publish_notices(records, gate, publisher, build):
    stats = dict(fetched=0, new=0, changed=0, unchanged=0, pending=0, missingId=0)
    for record in records:
        stats["fetched"] += 1
        token, reason, missing = gate.reserve(record)
        stats[reason] += 1
        stats["missingId"] += int(missing)
        if token is None:
            continue
        try:
            publisher.publish(
                gate.topic,
                record.key,
                build(record),
                on_delivery=lambda err, msg, token=token: gate.delivered(token, err is None),
            )
        except Exception:
            gate.delivered(token, False)
            raise
    return stats
