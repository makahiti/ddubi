"""S-DoT 10분 단위 유동인구 → ``city.raw``.

실시간 데이터셋의 기본 서비스명 ``IotVdata018``을 사용한다. 최신순 응답에서 페이지를 읽되
센서별 가장 최근 관측 한 건만 발행한다. 최근 한 달 전체를 10분마다 중복 발행하지 않으면서
서울 전역 센서가 첫 페이지에 모두 없을 때는 다음 페이지를 제한적으로 확인한다.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

import httpx

from config import ConfigError, Settings
from sources.api_utils import (
    collect_scopes,
    csv_values,
    first_value,
    page_size,
    parse_kst,
    safe_json,
    seoul_rows,
    seoul_url,
    total_count,
)
from sources.base import Record, SourceSpec, now_kst


def _sensor_id(item: dict[str, Any]) -> str | None:
    """응답 행에서 실제 장비를 구분할 수 있는 provider 센서 ID만 읽는다.

    ``MODEL_NM``은 여러 장비가 공유하는 기종명이라 ID 후보에서 제외한다. ID가 없다는 사실을
    ``unknown``이라는 가짜 공용 ID로 바꾸면 서로 다른 행이 한 센서로 합쳐지므로 ``None``을
    반환하고, 호출자가 미식별 원문 보존 경로로 분리한다.
    """

    value = first_value(item, ("SENSOR_ID", "SENS_ID", "S_DOT_NO", "SERIAL_NO"))
    return str(value) if value not in (None, "") else None


def _unidentified_key(item: dict[str, Any]) -> str:
    """센서 ID가 없는 원문 행을 Kafka/HDFS에서 개별 보존하기 위한 안정적 지문을 만든다.

    정렬 JSON의 SHA-256 앞 24자를 사용하므로 같은 원문 재수집은 같은 partition key를 갖고,
    서로 다른 시각·측정값의 행은 별도 이벤트로 남는다. 이 키는 canonical ID나 센서 ID가
    아니며 payload에도 ``identityStatus=MISSING``을 명시해 Redis 매핑에 사용되지 않게 한다.
    """

    canonical = json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]
    return f"unidentified:{digest}"


def records_from_rows(rows: list[dict[str, Any]]) -> list[Record]:
    """원본 행을 센서별 최신 이벤트로 축약한다.

    한 API 페이지에는 같은 센서의 여러 10분 관측이 섞일 수 있다. 실시간 상태 입력에는 최신
    한 건만 필요하므로 발생시각이 가장 큰 값을 남긴다. 원본 행 자체는 payload에 그대로 있어
    측정 단위와 품질 필드를 후속 Spark가 검증할 수 있다.
    """
    received = now_kst()
    latest: dict[str, Record] = {}
    unidentified: list[Record] = []
    for item in rows:
        sensor_id = _sensor_id(item)
        occurred = parse_kst(first_value(item, ("SENSING_TIME", "REGIST_DT", "DATA_TIME")), fallback=received)

        # ID가 없는 여러 행을 ``latest['unknown']`` 하나로 축약하지 않는다. 모두 Kafka/HDFS에
        # 남겨 제공처 품질 문제와 원본 건수를 재현하되 providerSensorId=None으로 두어 후속
        # Spark가 최신 Redis 키를 발급하지 못하게 보수적으로 실패시킨다.
        if sensor_id is None:
            unidentified.append(
                Record(
                    key=_unidentified_key(item),
                    event_type="sdot.population",
                    occurred_at=occurred,
                    payload={
                        "providerSensorId": None,
                        "identityStatus": "MISSING",
                        "raw": item,
                    },
                )
            )
            continue

        record = Record(
            key=sensor_id,
            event_type="sdot.population",
            occurred_at=occurred,
            payload={"providerSensorId": sensor_id, "identityStatus": "IDENTIFIED", "raw": item},
        )
        previous = latest.get(sensor_id)
        if previous is None or record.occurred_at > previous.occurred_at:
            latest[sensor_id] = record
    return list(latest.values()) + unidentified


def _positive_setting(settings: Settings, name: str, default: int, maximum: int) -> int:
    """호출량을 바꾸는 양의 정수 설정을 제한 범위 안에서 읽는다."""
    raw = settings.extra.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise ConfigError(f"{name}은 정수여야 합니다") from exc
    if not 1 <= value <= maximum:
        raise ConfigError(f"{name}은 1~{maximum} 범위여야 합니다")
    return value


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    key = settings.api_key("SEOUL_API_KEY")
    service = settings.extra.get("SDOT_SERVICE_NAME", "IotVdata018")
    districts = csv_values(settings, "SDOT_DISTRICTS", required=False) or [""]
    size = page_size(settings)
    max_pages = _positive_setting(settings, "SDOT_MAX_PAGES", 10, 100)
    # IotVdata018 원본 페이지의 공개 센서 수는 100대다. 126대 실시간 자료처럼 다른
    # 서비스명을 주입하는 경우 SDOT_EXPECTED_SENSOR_COUNT도 함께 변경한다.
    expected_sensors = _positive_setting(settings, "SDOT_EXPECTED_SENSOR_COUNT", 100, 10000)

    def fetch_district(district: str) -> list[Record]:
        rows: list[dict[str, Any]] = []
        filters = (district,) if district else ()
        for page_no in range(max_pages):
            start = page_no * size + 1
            end = start + size - 1
            body = safe_json(
                client.get(seoul_url(key, service, start, end, *filters)), provider="S-DoT 유동인구"
            )
            page_rows = seoul_rows(body, service)
            rows.extend(page_rows)

            # 전체 서울 조회는 공식 센서 수를 만족하면 과거 월 자료를 더 읽지 않는다. 자치구
            # 필터는 센서 수를 알 수 없으므로 짧은 페이지·전체 행 수·최대 페이지로 종료한다.
            # 미식별 원문 행은 모두 보존하지만 공식 센서 수 충족 계산에는 포함하지 않는다.
            # 포함하면 ID 없는 행이 많을 때 아직 읽지 않은 정상 센서가 있어도 조기 종료한다.
            identified_sensor_count = len({sensor_id for item in rows if (sensor_id := _sensor_id(item))})
            if not district and identified_sensor_count >= expected_sensors:
                break
            count = total_count(body, service)
            if len(page_rows) < size or (count is not None and end >= count):
                break
        return records_from_rows(rows)

    return collect_scopes(districts, fetch_district, provider="S-DoT 유동인구", scope_name="자치구")


SOURCE = SourceSpec("seoul.sdot.population", "city.raw", "city", 600, fetch)
