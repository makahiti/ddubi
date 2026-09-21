"""Spark의 Python 3.8에서 공유 Collector 코드의 런타임 호환성을 검증한다.

문법 파싱만으로는 타입 별칭의 list[T] 평가나 datetime.UTC import 오류를 잡지 못한다.
CI의 실제 인터프리터로 이벤트 모델과 등록된 수집 모듈을 import한다. 외부 API를 호출하거나
수집 작업을 시작하지 않는다. 향후 lint 자동 정리가 최소 실행 버전을 올리는 회귀를 방지한다.
"""

import importlib
from pathlib import Path


def test_shared_collector_contract_and_registered_sources_import_on_spark_runtime(monkeypatch):
    """이벤트 모델·타입 별칭·모든 등록 소스의 import가 현재 Spark 런타임에서 성공해야 한다."""
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2] / "collector" / "src"))
    events = importlib.import_module("events")
    poll_status = importlib.import_module("poll_status")
    adapter = importlib.import_module("sources.kafka_adapter")
    assert callable(events.build_event)
    assert callable(poll_status.build_poll_status)
    for name in adapter.POLICIES:
        module = importlib.import_module("sources." + name)
        assert module.__name__ == "sources." + name
