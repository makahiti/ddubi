"""도로 영향 후보가 반대 방향·미래 자료·오래된 관측으로 확정되는 회귀를 방어한다."""
import copy
import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from common.road_impact import RoadImpactIndex


class RoadImpactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.edge = dict(namespace='SEOUL_BUS',routeId='r',fromStopId='a',toStopId='b',
                         fromOrder=1,toOrder=2,direction=None,segmentId='BUS:r:a:b')
        bus=dict(idPolicy='canonical-adjacent-v2',masterVersion='test',edges=[self.edge])
        raw=gzip.compress(json.dumps(bus).encode());self.bus_sha=hashlib.sha256(raw).hexdigest()
        (self.root/'bus.gz').write_bytes(raw)
        self.path=dict(self.edge,validFrom='2026-09-11T00:00:00+09:00',validUntil='2026-09-12T00:00:00+09:00',
            evidenceMethod='OFFICIAL_ROUTE_LINKS',evidenceRef='fixture-only',coverage='COMPLETE',
            directionVerified=True,roadLinks=[dict(namespace='SEOUL_ACCINFO_LINK',linkId='east')])
        self.event=dict(eventType='traffic.incident',eventId='e1',source='seoul.traffic_incident',
            collectedAt='2026-09-11T10:00:00+09:00',payload=dict(providerIncidentId='i1',
            providerLinkNamespace='SEOUL_ACCINFO_LINK',providerLinkId='east',raw=dict(
            occurredDate='20260911',occurredTime='090000',expectedClearDate='20260911',expectedClearTime='110000')))

    def index(self, paths=None):
        road=dict(schemaVersion=1,version='test-road',masterVersion='test',busCrosswalkSha256=self.bus_sha,
                  mappings=[self.path] if paths is None else paths)
        raw=json.dumps(road).encode();(self.root/'road.json').write_bytes(raw)
        return RoadImpactIndex(self.root/'bus.gz',self.bus_sha,self.root/'road.json',hashlib.sha256(raw).hexdigest())

    def assess(self, events=None, edge=None, index=None):
        return (index or self.index()).assess(edge or self.edge,[self.event] if events is None else events,
            '2026-09-11T10:01:00+09:00','2026-09-11T10:05:00+09:00','2026-09-11T10:06:00+09:00',600)

    def test_matched_direction_is_risk_not_invented_delay_or_full_closure(self):
        result=self.assess()
        self.assertEqual(result['state'],'POTENTIAL_IMPACT')
        self.assertIsNone(result['blocked']);self.assertIsNone(result['delaySeconds'])
        self.assertEqual(result['causes'][0]['providerIncidentId'],'i1')

    def test_reverse_link_does_not_affect_same_named_road(self):
        self.event['payload']['providerLinkId']='west'
        self.assertEqual(self.assess()['state'],'NO_MATCHING_RECORDED_EVENT')

    def test_namespace_is_required_not_guessed_from_numeric_id(self):
        del self.event['payload']['providerLinkNamespace']
        self.assertIn('EVENT_LINK_UNRESOLVED',self.assess()['reasons'])

    def test_future_collection_excluded_from_replay(self):
        self.event['collectedAt']='2026-09-11T10:02:00+09:00'
        self.assertEqual(self.assess()['causes'],[])

    def test_stale_event_does_not_confirm_active_risk(self):
        self.event['collectedAt']='2026-09-11T09:00:00+09:00'
        self.assertIn('STALE_MATCHING_EVENT',self.assess()['reasons'])

    def test_expected_clear_is_not_observed_clear(self):
        self.event['payload']['raw']['expectedClearTime']='100000'
        self.assertIn('EXPECTED_CLEAR_NOT_CONFIRMED',self.assess()['reasons'])

    def test_reordered_duplicates_have_same_result(self):
        old=copy.deepcopy(self.event);old['collectedAt']='2026-09-11T09:59:00+09:00'
        self.assertEqual(self.assess([old,self.event,self.event]),self.assess([self.event,old]))

    def test_conflicting_same_timestamp_is_unknown(self):
        other=copy.deepcopy(self.event);other['payload']['raw']['expectedClearTime']='120000'
        self.assertIn('CONFLICTING_EVENT_REVISIONS',self.assess([self.event,other])['reasons'])

    def test_missing_path_remains_unknown(self):
        self.assertIn('ROAD_PATH_UNMAPPED_OR_EXPIRED',self.assess(index=self.index([]))['reasons'])

    def test_loop_occurrence_is_not_joined_on_segment_id_alone(self):
        edge=dict(self.edge,fromOrder=4,toOrder=5)
        self.assertIn('BUS_OCCURRENCE_MISMATCH',self.assess(edge=edge)['reasons'])

    def test_ambiguous_or_unverified_path_rejected(self):
        with self.assertRaises(ValueError):self.index([self.path,self.path])
        self.path['directionVerified']=False
        with self.assertRaises(ValueError):self.index()

    def test_tampered_artifact_rejected(self):
        self.index()
        with self.assertRaises(ValueError):
            RoadImpactIndex(self.root/'bus.gz','0'*64,self.root/'road.json','0'*64)


if __name__=='__main__':unittest.main()


def test_promotion_requires_reviewed_direction_and_sha(tmp_path):
    import runpy
    promote=runpy.run_path(str(Path(__file__).resolve().parents[2]/'backend/tools/road-impact/promote.py'))['promote']
    fixture=RoadImpactTest();fixture.setUp()
    try:
        reviewed=tmp_path/'reviewed.jsonl';reviewed.write_text(json.dumps(fixture.path)+'\n',encoding='utf-8')
        digest=hashlib.sha256(reviewed.read_bytes()).hexdigest();output=tmp_path/'road.json'
        report=promote(fixture.root/'bus.gz',fixture.bus_sha,reviewed,digest,'pilot',output)
        assert report['mappingCount']==1 and hashlib.sha256(output.read_bytes()).hexdigest()==report['sha256']
        import pytest
        with pytest.raises(FileExistsError): promote(fixture.root/'bus.gz',fixture.bus_sha,reviewed,digest,'pilot',output)
        broken=dict(fixture.path,directionVerified=False)
        reviewed.write_text(json.dumps(broken)+'\n',encoding='utf-8')
        with pytest.raises(ValueError,match='complete directional'):
            promote(fixture.root/'bus.gz',fixture.bus_sha,reviewed,hashlib.sha256(reviewed.read_bytes()).hexdigest(),'bad',tmp_path/'bad.json')
        assert not (tmp_path/'bad.json').exists()
    finally: fixture.doCleanups()
