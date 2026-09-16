"""수도권 대응표와 실제 Collector 봉투를 검증한다. 지역 간 같은 ID·순환 정류장 모호함은 성공으로 숨기지 않는다."""
import gzip
import hashlib
import json
import sys
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'src'))
from bus_crosswalk import BusCrosswalk
from sources.kafka_adapter import to_record, make_source
from sources.base import now_kst
from events import build_event


def artifact(tmp_path,ambiguous=False):
    edges=[]
    for ns in ['SEOUL_BUS','GBIS','INCHEON_PUBLIC_CSV']:
        prefix='' if ns=='SEOUL_BUS' else ns+'.'
        edges.append(dict(namespace=ns,routeId='1',fromStopId='10',toStopId='11',fromOrder=1,toOrder=2,
                          segmentId='BUS:'+':'.join(prefix+v for v in ['1','10','11']),direction='UP'))
    if ambiguous:edges.append(dict(namespace='SEOUL_BUS',routeId='1',fromStopId='12',toStopId='11',fromOrder=3,toOrder=4,
                                   segmentId='BUS:1:12:11',direction='UP'))
    payload=dict(schemaVersion=1,idPolicy='canonical-adjacent-v2',masterVersion='TEST',masterSha256='a'*64,edgeCount=len(edges),edges=edges)
    raw=gzip.compress(json.dumps(payload).encode(),mtime=0);path=tmp_path/'cw.json.gz';path.write_bytes(raw)
    return path,hashlib.sha256(raw).hexdigest()


def test_namespace_collision_and_collector_event_boundary(tmp_path):
    crosswalk=BusCrosswalk(*artifact(tmp_path))
    result=to_record('bus_arrival',{'busRouteId':'1','stId':'11','traTime1':'120'},crosswalk=crosswalk)
    event=build_event(make_source('bus_arrival'),result,now_kst())
    assert event['segmentIds']==['BUS:1:10:11']
    assert event['payload']['canonicalMapping']['scope']=='ARRIVAL_TARGET_INCOMING_EDGE'
    assert event['payload']['raw']['traTime1']=='120'
    assert crosswalk.resolve('bus.arrival',dict(providerNamespace='GBIS',providerRouteId='1',providerStopId='11'))['segmentIds']==['BUS:GBIS.1:GBIS.10:GBIS.11']


def test_event_schema_accepts_missing_crosswalk_and_rejects_unnamed_mapping_state(tmp_path):
    import jsonschema
    schema=json.loads((Path(__file__).resolve().parents[2]/'docs/contracts/events/transport-event.schema.json').read_text(encoding='utf-8'))
    for cw in [None,BusCrosswalk(*artifact(tmp_path))]:
        event=build_event(make_source('bus_arrival'),to_record('bus_arrival',{'busRouteId':'1','stId':'11'},crosswalk=cw),now_kst())
        jsonschema.validate(event,schema)
        event['payload']['canonicalMapping']['state']='EXACT'
        with pytest.raises(jsonschema.ValidationError):jsonschema.validate(event,schema)


def test_ambiguous_incoming_edges_and_unverified_section_order_are_not_mapped(tmp_path):
    crosswalk=BusCrosswalk(*artifact(tmp_path,True))
    result=to_record('bus_arrival',{'busRouteId':'1','stId':'11'},crosswalk=crosswalk)
    assert result.segment_ids is None
    assert result.payload['canonicalMapping']['state']=='AMBIGUOUS'
    position=to_record('bus_location',{'busRouteId':'1','vehId':'V','sectOrd':'2'},crosswalk=crosswalk)
    assert position.segment_ids is None


def test_explicit_position_pair_maps_and_missing_namespace_does_not(tmp_path):
    crosswalk=BusCrosswalk(*artifact(tmp_path))
    payload=dict(providerNamespace='SEOUL_BUS',providerRouteId='1',providerFromStopId='10',providerToStopId='11')
    assert crosswalk.resolve('bus.position',payload)['segmentIds']==['BUS:1:10:11']
    payload.pop('providerNamespace')
    assert not crosswalk.resolve('bus.position',payload)['segmentIds']


def test_tampered_digest_is_rejected(tmp_path):
    path,sha=artifact(tmp_path)
    with pytest.raises(ValueError,match='SHA'):BusCrosswalk(path,'0'*64)


def test_seoul_position_evidence_and_vehicle_partition_key(tmp_path):
    """운행 중인 차량의 순번과 출발점이 일치하면 Spark가 재검증할 pair를 전달한다."""
    cw = BusCrosswalk(*artifact(tmp_path))
    raw = dict(routeId='1', vehId='V', sectOrd='1', lastStnId='10', stopFlag='0')
    result = to_record('bus_location', raw, '1', cw)
    assert result.segment_ids == ['BUS:1:10:11']
    assert result.payload['providerFromStopId'] == '10'
    assert result.payload['providerToStopId'] == '11'
    assert result.key == to_record('bus_location', raw, '1').key
    assert result.payload['raw'] == raw
    schema=json.loads((Path(__file__).resolve().parents[2]/'docs/contracts/events/transport-event.schema.json').read_text(encoding='utf-8'))
    import jsonschema
    jsonschema.validate(build_event(make_source('bus_location'), result, now_kst()), schema)


@pytest.mark.parametrize('override,reason', [
    ({'stopFlag':'1'}, 'POSITION_NOT_BETWEEN_STOPS'),
    ({'sectOrd':'0'}, 'POSITION_SECTION_ORDER_MISSING_OR_INVALID'),
    ({'sectOrd':'1.5'}, 'POSITION_SECTION_ORDER_MISSING_OR_INVALID'),
    ({'sectOrd':None, 'stationSeq':'1'}, 'POSITION_SECTION_ORDER_MISSING_OR_INVALID'),
    ({'lastStnId':None}, 'POSITION_LAST_STOP_MISSING'),
    ({'sectOrd':'2'}, 'POSITION_SECTION_NOT_IN_MASTER'),
    ({'lastStnId':'11'}, 'POSITION_ORDER_STOP_MISMATCH'),
    ({'nextStId':'12'}, 'POSITION_NEXT_STOP_MISMATCH'),
    ({'routeId':'2'}, 'POSITION_ROUTE_CONTEXT_MISMATCH'),
])
def test_position_conflicting_or_missing_evidence_is_not_mapped(tmp_path, override, reason):
    cw = BusCrosswalk(*artifact(tmp_path))
    raw = dict(routeId='1', vehId='V', sectOrd='1', lastStnId='10', stopFlag='0')
    raw.update(override)
    result = to_record('bus_location', raw, '1', cw)
    assert result.segment_ids is None
    assert result.payload['canonicalMapping']['reason'] == reason
    assert 'providerFromStopId' not in result.payload


def test_shared_manifest_is_current_and_detects_tampering(tmp_path):
    """공통 묶음의 파일 유실/변조가 환경값 출력 전에 발견되어야 한다."""
    import importlib.util
    import shutil
    root = Path(__file__).resolve().parents[2]/'backend/transit-master'
    spec = importlib.util.spec_from_file_location('transit_manifest', root/'manage.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.check()['road']['status'] == 'REVIEW_REQUIRED'
    shutil.copytree(root, tmp_path/'master')
    with (tmp_path/'master/subway/metropolitan-subway-coordinates.json').open('ab') as stream:
        stream.write(b' ')
    with pytest.raises(ValueError, match='manifest mismatch'):
        module.check(tmp_path/'master')


def test_serving_artifact_has_valid_ids_in_every_source():
    root=Path(__file__).resolve().parents[2]
    path=root/'backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz'
    manifest=json.loads(path.with_suffix('.manifest.json').read_text(encoding='utf-8'))
    crosswalk=BusCrosswalk(path,manifest['sha256'])
    assert crosswalk.version==manifest['masterVersion']
    assert {key[0] for key in crosswalk.by_pair}=={'SEOUL_BUS','GBIS','INCHEON_PUBLIC_CSV'}
