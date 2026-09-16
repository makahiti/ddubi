"""버전 고정 정적 대응표를 원천 관측에 연결하는 읽기 전용 모듈.

기동 시 gzip SHA·ID 정책·본문 ID와 순번을 검증하고 노선/정류장으로 인덱싱한다.
위치의 sectOrd 의미를 추측하지 않으며, 도착 대상 진입 edge를 차량의 현재 구간으로
해석하지 않는다. 비설정·모호함은 상태와 빈 segmentIds로 전달해 원문은 Kafka에 보존한다.
"""
import gzip
import hashlib
import json
import re
from collections import defaultdict

POLICY = 'canonical-adjacent-v2'


class BusCrosswalk:
    """프로세스가 소유하는 불변 버전의 조회 인덱스. 파일 교체는 재시작으로 명시한다."""
    def __init__(self, path, expected_sha):
        with open(path, 'rb') as stream:
            raw=stream.read()
        if not expected_sha or hashlib.sha256(raw).hexdigest()!=expected_sha:
            raise ValueError('crosswalk SHA mismatch')
        with gzip.GzipFile(fileobj=__import__('io').BytesIO(raw)) as stream:
            content=stream.read(160_000_001)
        if len(content)>160_000_000:
            raise ValueError('crosswalk too large')
        data=json.loads(content)
        if data.get('schemaVersion')!=1 or data.get('idPolicy')!=POLICY or not data.get('masterVersion'):
            raise ValueError('crosswalk policy/version mismatch')
        if not re.fullmatch('[a-f0-9]{64}',data.get('masterSha256','')) or data.get('edgeCount')!=len(data['edges']):
            raise ValueError('crosswalk manifest mismatch')
        self.version=data['masterVersion'];self.master_sha=data['masterSha256']
        self.sha=expected_sha;self.by_arrival=defaultdict(set);self.by_pair=defaultdict(set)
        self.by_order = {}
        seen=set()
        for e in data['edges']:
            ns=e['namespace'];parts=[e['routeId'],e['fromStopId'],e['toStopId']]
            if ns not in {'SEOUL_BUS','GBIS','INCHEON_PUBLIC_CSV'} or any(not re.fullmatch('[A-Za-z0-9._-]{1,50}',v) for v in parts):
                raise ValueError('crosswalk source ID invalid')
            canonical=[v if ns=='SEOUL_BUS' else ns+'.'+v for v in parts]
            if any(len(v)>50 for v in canonical) or e['segmentId']!='BUS:'+':'.join(canonical):
                raise ValueError('crosswalk canonical ID mismatch')
            if e['fromOrder']<1 or e['toOrder']!=e['fromOrder']+1:
                raise ValueError('crosswalk sequence invalid')
            key=(ns,e['routeId'],e['fromOrder'])
            if key in seen:raise ValueError('duplicate crosswalk order')
            seen.add(key)
            self.by_order[key] = dict(e)
            self.by_arrival[(ns,e['routeId'],e['toStopId'])].add(e['segmentId'])
            self.by_pair[(ns,*parts)].add(e['segmentId'])

    def seoul_position_pair(self, route_id, raw):
        """서울 위치 응답의 순번과 마지막 정류장 증거가 일치하는 인접 pair를 만든다.

        공식 getBusPosByRtid의 sectOrd는 구간순번이며 stationSeq와 동치로
        간주하지 않는다. 1. 운행 상태 확인 → 2. 고정 마스터 순번 후보 검색 →
        3. lastStnId로 출발점 대조 순서로 검증한다. 순번만 있거나 정차 중이면
        현재 edge를 확정하지 않는다. 원본·시각은 변경하지 않고 실패 사유를 반환한다.
        순환/회차에서도 해당 occurrence만 사용하며 종점에서 처음으로 감지 않는다.
        """
        if str(raw.get('stopFlag', '')) != '0':
            return {}, 'POSITION_NOT_BETWEEN_STOPS'
        order = str(raw.get('sectOrd', ''))
        if not re.fullmatch(r'[1-9][0-9]{0,9}', order):
            return {}, 'POSITION_SECTION_ORDER_MISSING_OR_INVALID'
        last = str(raw.get('lastStnId') or '')
        if not last:
            return {}, 'POSITION_LAST_STOP_MISSING'
        edge = self.by_order.get(('SEOUL_BUS', route_id, int(order)))
        if edge is None:
            return {}, 'POSITION_SECTION_NOT_IN_MASTER'
        # 순번의 시작/끝 해석 또는 노선 개편으로 달라진 마스터를 자동 보정하지 않는다.
        if edge['fromStopId'] != last:
            return {}, 'POSITION_ORDER_STOP_MISMATCH'
        if raw.get('nextStId') and str(raw['nextStId']) != edge['toStopId']:
            return {}, 'POSITION_NEXT_STOP_MISMATCH'
        return {'providerFromStopId': edge['fromStopId'],
                'providerToStopId': edge['toStopId']}, 'NONE'

    def resolve(self, event_type, payload):
        """명시적 provider namespace와 ID로 유일한 edge만 반환한다. payload·원본은 변경하지 않는다."""
        ns=payload.get('providerNamespace');rid=payload.get('providerRouteId')
        scope='NONE';candidates=set()
        if event_type=='bus.arrival':
            scope='ARRIVAL_TARGET_INCOMING_EDGE'
            candidates=self.by_arrival.get((ns,rid,payload.get('providerStopId')),set())
        elif event_type=='bus.position':
            scope='EXPLICIT_ADJACENT_STOP_PAIR'
            candidates=self.by_pair.get((ns,rid,payload.get('providerFromStopId'),payload.get('providerToStopId')),set())
        state='CONFIRMED' if len(candidates)==1 else 'AMBIGUOUS' if candidates else 'UNRESOLVED'
        reason='NONE' if state=='CONFIRMED' else 'MULTIPLE_INCOMING_EDGES' if candidates else 'PROVIDER_CROSSWALK_NOT_FOUND'
        return dict(segmentIds=sorted(candidates) if state=='CONFIRMED' else [],masterVersion=self.version,
                    idPolicy=POLICY,crosswalkSha256=self.sha,state=state,scope=scope,reason=reason)
