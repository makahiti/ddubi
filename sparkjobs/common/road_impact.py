"""도로 영향 검토용 순수 전처리. 운영 상태/ML 입력을 자동 변경하지 않는다.

고정 SHA의 v6 버스 대응표와 별도 검증 도로 대응표를 읽어 경로 edge별 위험 근거를 만든다.
시각은 offset 필수 ISO8601, 유효기간은 [시작, 종료), 소요시간 추정·서비스 DB 쓰기는 없다.
학습 Replay와 예측 준비에서 같은 함수를 사용한다. 호출자가 당시 수집된 이력과 cutoff를
주입해야 한다. 공지 문자열/정류장 직선으로 통제 구간을 추정하지 않는다.
"""
import gzip
import re
import hashlib
import json
from datetime import datetime, timedelta
from pathlib import Path

POLICY = 'road-impact-review-v1'


def time_value(value):
    """시간대 없는 시각은 추측하지 않고 거부한다. 모든 비교는 aware datetime끼리 한다."""
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('timezone required')
    return result


def _read(path, sha, compressed=False):
    raw = Path(path).read_bytes()
    if not sha or hashlib.sha256(raw).hexdigest() != sha:
        raise ValueError('artifact SHA mismatch')
    return json.loads(gzip.decompress(raw) if compressed else raw)


def edge_key(row):
    """순환 노선의 같은 정류장 쌍도 fromOrder로 구별한다. canonical ID만 조인하지 않는다."""
    return row['namespace'], row['routeId'], row['fromOrder']


class RoadImpactIndex:
    """검증된 정적 edge만 인덱싱한다. 원천 관측 대량 collect용 클래스가 아니다.

    road artifact는 버스 SHA/버전, 적용 기간, 방향 검증한 전체 도로 링크 목록과 근거를
    갖는다. 미확인 후보는 이 파일에 넣지 않는다. 입력 오류는 기동 시 거부한다.
    """
    def __init__(self, bus_path, bus_sha, road_path, road_sha):
        bus = _read(bus_path, bus_sha, True)
        road = _read(road_path, road_sha)
        if bus.get('idPolicy') != 'canonical-adjacent-v2' or road.get('schemaVersion') != 1:
            raise ValueError('unsupported policy/schema')
        if road.get('busCrosswalkSha256') != bus_sha or road.get('masterVersion') != bus.get('masterVersion'):
            raise ValueError('bus master mismatch')
        if not isinstance(road.get('version'),str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,64}',road['version']):
            raise ValueError('invalid road version')
        self.version = road['version']
        self.sha = road_sha
        self.bus_sha = bus_sha
        self.master_version = bus['masterVersion']
        self.edges = {edge_key(e): e for e in bus['edges']}
        if len(self.edges) != len(bus['edges']):
            raise ValueError('duplicate bus occurrence')
        self.paths = {}
        for row in road['mappings']:
            key = edge_key(row)
            edge = self.edges.get(key)
            if edge is None or any(row.get(k) != edge.get(k) for k in
                                   ('segmentId', 'fromStopId', 'toStopId', 'toOrder', 'direction')):
                raise ValueError('road mapping not in bus master')
            if row.get('evidenceMethod') not in ('OFFICIAL_ROUTE_LINKS', 'REVIEWED_ROUTE_GEOMETRY') or not row.get('evidenceRef'):
                raise ValueError('route evidence required')
            if row.get('coverage') != 'COMPLETE' or row.get('directionVerified') is not True:
                raise ValueError('complete directional path required')
            start, end = time_value(row['validFrom']), time_value(row['validUntil'])
            if start >= end or not row['roadLinks']:
                raise ValueError('invalid path validity')
            links = row['roadLinks']
            if any(not isinstance(link.get(k),str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,50}',link[k]) for link in links for k in ('namespace','linkId')):
                raise ValueError('namespaced road IDs required')
            for old in self.paths.get(key, []):
                if start < time_value(old['validUntil']) and time_value(old['validFrom']) < end:
                    raise ValueError('ambiguous overlapping mappings')
            self.paths.setdefault(key, []).append(row)

    def assess(self, edge, events, as_of, passage_start, passage_end, max_age_seconds):
        """1개 경로 edge의 사고 영향 후보와 미확인 사유를 반환한다.

        events: Collector city.raw envelope 목록. cutoff 후 수집은 제외하고 사고 ID별 마지막
        응답을 선택한다. 같은 시각의 상충 응답은 미확인이다. 정책 주기를 인자로 받아 만료를
        판단하며 기본값을 강제하지 않는다. 예상 해제시각 경과는 실제 해제 증거가 아니다.
        NO_MATCHING_RECORDED_EVENT도 무사고/통행 가능 보장이 아니므로 blocked와 지연초는 null.
        """
        cutoff, start, end = map(time_value, (as_of, passage_start, passage_end))
        if start < cutoff or end <= start or max_age_seconds <= 0:
            raise ValueError('invalid passage/freshness interval')
        result = dict(policyVersion=POLICY, masterVersion=self.master_version,
                      busCrosswalkSha256=self.bus_sha, roadCrosswalkVersion=self.version,
                      roadCrosswalkSha256=self.sha, segmentId=edge.get('segmentId'),
                      fromOrder=edge.get('fromOrder'), asOf=as_of,
                      passageStart=passage_start, passageEnd=passage_end,
                      state='UNKNOWN', reasons=[], causes=[], blocked=None, delaySeconds=None)
        key = edge_key(edge)
        expected = self.edges.get(key)
        if expected is None or any(edge.get(k) != expected.get(k) for k in ('segmentId', 'fromStopId', 'toStopId', 'toOrder', 'direction')):
            result['reasons'] = ['BUS_OCCURRENCE_MISMATCH']
            return result
        path = next((p for p in self.paths.get(key, [])
                     if time_value(p['validFrom']) <= start and end <= time_value(p['validUntil'])), None)
        if path is None:
            result['reasons'] = ['ROAD_PATH_UNMAPPED_OR_EXPIRED']
            return result
        links = {(x['namespace'], x['linkId']) for x in path['roadLinks']}
        latest = {}
        for event in events:
            if event.get('eventType') != 'traffic.incident':
                continue
            try:
                collected = time_value(event['collectedAt'])
                payload = event['payload']
                identity = payload['providerIncidentId']
                if collected > cutoff:
                    continue
                if not identity:
                    raise ValueError('missing incident ID')
                k = (event.get('source'), identity)
                fingerprint = json.dumps(payload, sort_keys=True, ensure_ascii=False)
                old = latest.get(k)
                if old is None or old[0] < collected:
                    latest[k] = (collected, fingerprint, event, False)
                elif old[0] == collected and old[1] != fingerprint:
                    latest[k] = (old[0], old[1], old[2], True)
            except (KeyError, TypeError, ValueError):
                result['reasons'].append('INVALID_EVENT')
        for collected, _, event, conflict in latest.values():
            payload = event['payload']
            link = (payload.get('providerLinkNamespace'), payload.get('providerLinkId'))
            if not all(link):
                result['reasons'].append('EVENT_LINK_UNRESOLVED')
                continue
            if link not in links:
                continue
            if conflict:
                result['reasons'].append('CONFLICTING_EVENT_REVISIONS')
                continue
            if cutoff - collected > timedelta(seconds=max_age_seconds):
                result['reasons'].append('STALE_MATCHING_EVENT')
                continue
            raw = payload.get('raw', {})
            try:
                occurred = datetime.strptime(raw['occurredDate'] + raw['occurredTime'], '%Y%m%d%H%M%S').replace(tzinfo=time_value('2000-01-01T00:00:00+09:00').tzinfo)
                if occurred > cutoff:
                    result['reasons'].append('FUTURE_INCIDENT_START')
                    continue
                expiry = None
                if raw.get('expectedClearDate') or raw.get('expectedClearTime'):
                    expiry = datetime.strptime(raw['expectedClearDate'] + raw['expectedClearTime'], '%Y%m%d%H%M%S').replace(tzinfo=occurred.tzinfo)
                    if expiry <= occurred:
                        raise ValueError('invalid expected clear')
                if expiry is not None and expiry <= start:
                    result['reasons'].append('EXPECTED_CLEAR_NOT_CONFIRMED')
                    continue
            except (KeyError, TypeError, ValueError):
                result['reasons'].append('INVALID_INCIDENT_INTERVAL')
                continue
            result['causes'].append(dict(eventId=event.get('eventId'),
                providerIncidentId=payload['providerIncidentId'], linkNamespace=link[0], linkId=link[1],
                collectedAt=event['collectedAt'], incidentTypeCode=raw.get('incidentTypeCode'),
                expectedClearAt=expiry.isoformat() if expiry else None,
                evidenceRef=path['evidenceRef']))
        result['reasons'] = sorted(set(result['reasons']))
        result['causes'].sort(key=lambda c: (c['linkNamespace'], c['linkId'], c['providerIncidentId']))
        if result['causes']:
            result['state'] = 'POTENTIAL_IMPACT'
        elif not result['reasons']:
            result['state'] = 'NO_MATCHING_RECORDED_EVENT'
            result['reasons'] = ['FEED_COVERAGE_NOT_PROVEN']
        return result
