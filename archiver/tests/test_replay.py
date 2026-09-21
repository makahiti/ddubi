"""replay 를 가짜 아카이브(로컬 Parquet)와 가짜 프로듀서로 검증한다. Kafka·HDFS 불필요."""

from datetime import datetime

import pyarrow as pa
import pyarrow.parquet as pq
from buffer import KST, SCHEMA
from replay import Pacer, list_archive_files, parse_args, run
from storage import Storage


def write_fake_parquet(storage, topic, date, hour, rows):
    """rows: [(key, value, 시각)] 을 아카이브와 같은 형식의 Parquet 으로 만든다."""
    data = [{"kafka_key": k, "value": v, "kafka_timestamp": ts} for k, v, ts in rows]
    table = pa.Table.from_pylist(data, schema=SCHEMA)  # 빠진 컬럼은 null 로 채워진다
    path = f"{storage.root}/{topic}/date={date}/hour={hour:02d}/{topic}-x{hour}.parquet"
    storage.fs.makedirs(path.rsplit("/", 1)[0], exist_ok=True)
    with storage.fs.open(path, "wb") as f:
        pq.write_table(table, f)


class FakeProducer:
    """진짜 우체부 대역: 부친 것을 리스트에 모아두고 즉시 성공 영수증을 준다."""

    def __init__(self):
        self.produced = []

    def produce(self, topic, key, value, on_delivery):
        self.produced.append((topic, key, value))
        on_delivery(None, None)

    def poll(self, timeout):
        pass

    def flush(self, timeout):
        pass


def make_args(**over):
    args = parse_args(
        [
            "--source-topic",
            "city.raw",
            "--target-topic",
            "city.replay",
            "--from-date",
            "2026-09-01",
            "--to-date",
            "2026-09-01",
            "--rate",
            "100000",  # 테스트에선 사실상 안 자게 아주 크게
        ]
    )
    for key, value in over.items():
        setattr(args, key, value)
    return args


def ts(hour, minute):
    return datetime(2026, 9, 1, hour, minute, tzinfo=KST)


def test_end_to_end_order_and_content(tmp_path):
    storage = Storage.from_uri(f"file://{tmp_path}")
    # 늦은 시간 파일을 먼저 만들고, 파일 안 순서도 뒤집어 둔다 — 정렬이 고치는지 보려고
    write_fake_parquet(storage, "city.raw", "2026-09-01", 11, [("k3", "v3", ts(11, 0))])
    write_fake_parquet(storage, "city.raw", "2026-09-01", 10, [("k2", "v2", ts(10, 30)), ("k1", "v1", ts(10, 0))])

    fake = FakeProducer()
    counters = run(make_args(), storage, fake)

    assert [p[1] for p in fake.produced] == ["k1", "k2", "k3"]  # 시간순 발행
    assert fake.produced[0] == ("city.replay", "k1", "v1")  # 대상 토픽 + 원본 무변형
    assert counters == {"sent": 3, "failed": 0}


def test_date_filter(tmp_path):
    storage = Storage.from_uri(f"file://{tmp_path}")
    write_fake_parquet(storage, "city.raw", "2026-08-31", 10, [("old", "x", ts(10, 0))])
    write_fake_parquet(storage, "city.raw", "2026-09-01", 10, [("new", "x", ts(10, 0))])
    files = list_archive_files(storage, "city.raw", "2026-09-01", "2026-09-01")
    assert len(files) == 1
    assert "date=2026-09-01" in files[0]


def test_limit_stops_early(tmp_path):
    storage = Storage.from_uri(f"file://{tmp_path}")
    write_fake_parquet(storage, "city.raw", "2026-09-01", 10, [(f"k{i}", "v", ts(10, i)) for i in range(10)])
    fake = FakeProducer()
    run(make_args(limit=4), storage, fake)
    assert len(fake.produced) == 4


def test_repeat_replays_archive_n_times(tmp_path):
    storage = Storage.from_uri(f"file://{tmp_path}")
    write_fake_parquet(storage, "city.raw", "2026-09-01", 10, [("k1", "v1", ts(10, 0)), ("k2", "v2", ts(10, 10))])
    fake = FakeProducer()
    counters = run(make_args(repeat=3), storage, fake)
    assert [p[1] for p in fake.produced] == ["k1", "k2"] * 3  # 같은 순서로 3번
    assert counters == {"sent": 6, "failed": 0}


def test_limit_caps_total_across_repeats(tmp_path):
    storage = Storage.from_uri(f"file://{tmp_path}")
    write_fake_parquet(storage, "city.raw", "2026-09-01", 10, [(f"k{i}", "v", ts(10, i)) for i in range(4)])
    fake = FakeProducer()
    run(make_args(repeat=10, limit=7), storage, fake)
    assert len(fake.produced) == 7  # 4 + 3, 반복 포함 총량에서 멈춘다


def test_dry_run_produces_nothing(tmp_path):
    storage = Storage.from_uri(f"file://{tmp_path}")
    write_fake_parquet(storage, "city.raw", "2026-09-01", 10, [("k", "v", ts(10, 0))])
    counters = run(make_args(), storage, producer=None)
    assert counters == {"sent": 0, "failed": 0}


def test_pacer_rate_math():
    slept = []
    p = Pacer(rate=10, clock=lambda: 100.0, sleep=slept.append)  # 시계는 100 에 고정
    for i in range(5):
        p.wait(ts(10, i))
    # 초당 10건: n번째 이벤트의 목표는 시작+n/10초. 시계가 멈춰 있으니 잠은 0, 0.1, 0.2, 0.3, 0.4
    assert [round(s, 3) for s in slept] == [0.1, 0.2, 0.3, 0.4]
    assert abs(sum(slept) - 1.0) < 1e-9


def test_pacer_speed_math():
    slept = []
    p = Pacer(speed=60, sleep=slept.append)
    p.wait(ts(10, 0))  # 첫 건은 안 잔다
    p.wait(ts(10, 10))  # 원본 10분 간격 → 60배속 → 10초
    p.wait(ts(10, 10))  # 같은 시각(간격 0) → 안 잔다
    assert slept == [10.0]
