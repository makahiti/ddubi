"""아카이브(Parquet)를 Kafka 로 재전송하는 재생기. 부하 실험·시연용."""

import argparse
import logging
import os
import sys
import time

log = logging.getLogger("replay")


def parse_args(argv):
    p = argparse.ArgumentParser(description="아카이브 재생기")
    p.add_argument("--source-topic", required=True)  # 예: city.raw
    p.add_argument("--target-topic", required=True)  # 예: city.replay
    p.add_argument("--from-date", required=True)  # YYYY-MM-DD
    p.add_argument("--to-date", required=True)  # 포함
    speed = p.add_mutually_exclusive_group(required=True)  # 둘 중 하나만
    speed.add_argument("--rate", type=float, help="초당 발행 건수")
    speed.add_argument("--speed", type=float, help="원본 대비 배속")
    p.add_argument("--limit", type=int, default=100_000, help="안전장치: 최대 발행 수 (반복 포함 총량)")
    # 아카이브 1회 통과 분량(수천 건)으로는 부하 실험이 안 되어 같은 파일들을 N번 반복 재생한다.
    # speed 모드에서는 회차 경계에서 원본 시각이 되돌아가므로 그 지점만 간격 없이 이어진다
    p.add_argument("--repeat", type=int, default=1, help="아카이브 반복 재생 횟수 (부하 실험용)")
    p.add_argument("--dry-run", action="store_true")
    return p.parse_args(argv)


def list_archive_files(storage, topic, from_date, to_date):
    """날짜 범위에 해당하는 Parquet 파일 경로를 시간순으로 돌려준다.

    아카이브 경로가 {topic}/date=YYYY-MM-DD/hour=HH/파일 형식이라,
    경로 문자열을 그냥 정렬하면 그게 곧 시간순이다.
    """
    pattern = f"{storage.root}/{topic}/date=*/hour=*/*.parquet"
    selected = []
    for path in storage.fs.glob(pattern):
        date_str = path.split("date=")[1][:10]  # 경로에서 "2026-09-01" 부분만 추출
        if from_date <= date_str <= to_date:  # 문자열 비교 = 날짜 비교 (형식 덕분)
            selected.append(path)
    return sorted(selected)


def iter_events(storage, files):
    """파일들을 차례로 열어 (key, value, 원본시각) 을 한 건씩 낸다 (제너레이터).

    컬럼 이름은 아카이버 SCHEMA(buffer.py) 기준:
    kafka_key(문자열), value(원본 JSON 문자열), kafka_timestamp(브로커 도착 시각).
    value 는 열어보지 않고 그대로 통과시킨다 (원본 무변형).
    """
    for path in files:
        table = storage.read_parquet(path, columns=["kafka_key", "value", "kafka_timestamp"])  # 재시도 포함
        table = table.sort_by("kafka_timestamp")  # 파일 안 순서 보정
        for row in table.to_pylist():
            yield row["kafka_key"], row["value"], row["kafka_timestamp"]


class Pacer:
    """이벤트 사이에서 얼마나 잘지 계산한다. rate(초당 고정) 또는 speed(배속) 중 하나로 동작.

    - rate 모드: 목표 시각표(시작 + n/rate)를 만들어 그 시각까지 잔다.
      매번 sleep(1/rate) 하면 처리 시간이 더해져 느려지지만, 이 방식은 오차가 누적되지 않는다.
    - speed 모드: 원본 이벤트 간격을 배속으로 나눠 잔다 (10분 간격을 60배속이면 10초).
    clock/sleep 을 주입받는 것은 테스트 때 진짜로 잠들지 않기 위해서다.
    time.monotonic 을 쓰는 이유: time.time() 은 시스템 시각이라 NTP 보정으로 뒤로 갈 수
    있지만 monotonic 은 역행하지 않는 스톱워치라 간격 계산에 안전하다.
    """

    def __init__(self, rate=None, speed=None, clock=time.monotonic, sleep=time.sleep):
        self.rate = rate
        self.speed = speed
        self.clock = clock
        self.sleep = sleep
        self.started_at = None  # rate 모드: 재생 시작 시각
        self.count = 0  # 지금까지 처리한 건수
        self.prev_event_ts = None  # speed 모드: 직전 이벤트의 원본 시각

    def wait(self, event_ts):
        if self.rate:
            now = self.clock()
            if self.started_at is None:
                self.started_at = now
            due = self.started_at + self.count / self.rate  # 이 이벤트의 목표 발행 시각
            if due > now:
                self.sleep(due - now)
        else:
            if self.prev_event_ts is not None:
                gap = (event_ts - self.prev_event_ts).total_seconds() / self.speed
                if gap > 0:
                    self.sleep(gap)
            self.prev_event_ts = event_ts
        self.count += 1


def make_producer(bootstrap_servers):
    from confluent_kafka import Producer  # 지연 import: dry-run·테스트에선 불필요

    return Producer(
        {
            "bootstrap.servers": bootstrap_servers,
            "acks": "all",  # 브로커 저장 완료까지 확인
            "enable.idempotence": True,  # 재시도로 인한 중복 발행 방지
            "linger.ms": 50,  # 50ms 모아 묶음 전송 (배칭 — 고속의 비결)
            "compression.type": "lz4",
        }
    )


def run(args, storage, producer):
    """읽기 → 속도 조절 → 발행. producer=None 이면 dry-run(읽기만). 결과 카운터를 돌려준다."""
    counters = {"sent": 0, "failed": 0}

    def on_delivery(err, msg):  # 발행 결과 영수증
        counters["failed" if err else "sent"] += 1

    files = list_archive_files(storage, args.source_topic, args.from_date, args.to_date)
    repeat = max(1, getattr(args, "repeat", 1))
    log.info(
        "파일 %d개에서 재생 시작 (limit=%d, repeat=%d, %s)",
        len(files),
        args.limit,
        repeat,
        f"rate={args.rate}/s" if args.rate else f"speed=x{args.speed}",
    )
    pacer = Pacer(rate=args.rate, speed=args.speed)

    published = 0
    started = time.monotonic()
    for pass_no in range(1, repeat + 1):
        if repeat > 1:
            log.info("회차 %d/%d 시작", pass_no, repeat)
        for key, value, ts in iter_events(storage, files):
            if published >= args.limit:
                break
            pacer.wait(ts)
            if producer is not None:
                producer.produce(args.target_topic, key=key, value=value, on_delivery=on_delivery)
                producer.poll(0)  # 영수증(콜백) 수거 — 안 하면 내부 큐가 찬다
            published += 1
            if published % 1000 == 0:
                elapsed = time.monotonic() - started
                log.info("진행 %d건 (평균 %.0f건/초)", published, published / max(elapsed, 0.001))
        if published >= args.limit:
            log.info("limit %d 도달, 중단", args.limit)
            break

    if producer is not None:
        producer.flush(30)  # 남은 묶음을 마저 보내고 대기
    log.info("완료 발행=%d 성공=%d 실패=%d", published, counters["sent"], counters["failed"])
    return counters


def main(argv):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    args = parse_args(argv)
    from storage import Storage

    storage = Storage.from_uri(os.environ["ARCHIVE_URI"], os.environ.get("ARCHIVE_HDFS_USER"))
    log.info(
        "replay %s -> %s (%s..%s)%s",
        args.source_topic,
        args.target_topic,
        args.from_date,
        args.to_date,
        " [dry-run]" if args.dry_run else "",
    )
    producer = None if args.dry_run else make_producer(os.environ["KAFKA_BOOTSTRAP_SERVERS"])
    run(args, storage, producer)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) or 0)
