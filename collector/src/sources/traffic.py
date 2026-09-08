"""기존 ``collector-traffic``와 호환되는 TOPIS 소통·돌발 통합 수집기.

두 종류가 서로 다른 토픽을 사용하므로 이 호환 모듈은 ``transport.raw``에 실시간 소통만
발행한다. 돌발정보는 반드시 ``COLLECTOR_SOURCE=traffic_incident`` 별도 프로세스로 실행한다.
"""

from sources.traffic_realtime import SOURCE, fetch, records_from_rows
