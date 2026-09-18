import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec=importlib.util.spec_from_file_location('transit_export',Path(__file__).resolve().parents[1]/'infra/scripts/export-transit-reference.py')
export=importlib.util.module_from_spec(spec);spec.loader.exec_module(export)
verify_spec=importlib.util.spec_from_file_location('transit_verify',Path(__file__).resolve().parents[1]/'infra/scripts/verify-transit-reference.py')
verify=importlib.util.module_from_spec(verify_spec);verify_spec.loader.exec_module(verify)


class TransitReferenceTests(unittest.TestCase):
    def test_exact_lookup_search_and_tamper(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'package';writer=export.Writer(root,'ROAD','source-v1',['UNVERIFIED'])
            refs=[writer.add('ROAD_OCCURRENCE',[i],{'productionReady':False},'source',routeId='123') for i in range(3)]
            writer.finish({'occurrenceCount':3})
            sha=export.digest(root/'manifest.json')
            self.assertEqual(export.lookup(root,refs[0],sha)['referenceId'],refs[0])
            self.assertTrue(export.search(root,'routeId','123',2)['truncated'])
            with self.assertRaises(ValueError):export.lookup(root,refs[0],'0'*64)
            with self.assertRaises(ValueError):export.lookup(root,'../../'+refs[0],sha)
            with self.assertRaises(ValueError):export.lookup(root,'SUBWAY_PATTERN_'+refs[0][-64:],sha)
            manifest=json.loads((root/'manifest.json').read_text());shard=root/manifest['entriesIndex']['shards'][refs[0][-64:-62]]['path'];shard.write_text('{}')
            with self.assertRaises(ValueError):export.lookup(root,refs[0],sha)

    def test_path_escape_and_reference_promotion_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            with self.assertRaises(ValueError):export.safe_relative(root,'../outside')
            with self.assertRaises(ValueError):export.safe_relative(root,root.parent)
            writer=export.Writer(root/'package','ROAD','v1',[])
            with self.assertRaises(ValueError):writer.add('ROAD_OCCURRENCE',[1],{'productionReady':True},'v1')
            writer.db.close();writer.temp.cleanup()

    def test_master_compatibility_is_exact_and_not_an_approval(self):
        edge=dict(namespace='SEOUL_BUS',routeId='1',fromOrder=1,toOrder=2,fromStopId='a',toStopId='b',direction=None,segmentId='x')
        index={export.master_key(edge):[dict(edge)]}
        self.assertEqual(export.compatibility(edge,index),'EXACT_MATCH_CURRENT_MASTER')
        index[export.master_key(edge)][0]['direction']='UP'
        self.assertEqual(export.compatibility(edge,index),'OCCURRENCE_FIELDS_CHANGED')
        index[export.master_key(edge)].append(edge)
        self.assertEqual(export.compatibility(edge,index),'AMBIGUOUS_CURRENT_MASTER')
        self.assertEqual(export.compatibility(edge,{}),'MISSING_CURRENT_MASTER')

    def fixture(self,root):
        subway=root/'source-subway';subway.mkdir();road=root/'source-road';road.mkdir()
        pattern=dict(sourcePatternId='pattern1',productionReady=False,route={'route_id':'r1','route_long_name':'참고노선'},region='지역')
        availability={k:None for k in ('sourcePublishedAt','sourceAvailableAt','currentValidFrom','currentValidUntil')}
        export.write_json(subway/'reference-master-bundle.json',dict(productionReady=False,usage='UNDATED_SCHEDULE_REFERENCE',availability=availability,limitations=['CURRENT_TIMETABLE_VALIDITY_UNVERIFIED'],masterDataVersion='sub-master',patterns=[pattern]))
        segment=dict(sourcePatternId='pattern1',serviceId='B1',sequence=1,productionReady=False,scheduledMeanSeconds=100,canonicalSegmentId=None)
        headway=dict(segment,serviceElapsedHour=8)
        export.write_json(subway/'scheduled-segment-reference.jsonl',segment);export.write_json(subway/'scheduled-headway-reference.jsonl',headway)
        export.write_json(subway/'complete.json',dict(outputs={p.name:export.digest(p) for p in subway.iterdir()},summary={'patternCount':1,'referenceEdgeCount':1,'headwayBucketCount':1}))
        edge=dict(namespace='SEOUL_BUS',routeId='1',fromOrder=1,toOrder=2,fromStopId='a',toStopId='b',direction=None,segmentId='x')
        batch=road/'batch/occurrence';batch.mkdir(parents=True)
        record=dict(edge=edge,shapeId='shape1',fromShapeVertex=0,toShapeVertex=1,productionReady=False,runtimeMappingApproved=False,candidatePath={'state':'SINGLE_CONNECTED_CANDIDATE_PATH','path':[]},reviewSamples=[{'heavy':'data'}])
        export.write_json(batch/'occurrence-candidates.jsonl',record);export.write_json(batch/'report.json',{'masterVersion':'old'})
        files={'occurrence/'+p.name:export.digest(p) for p in batch.iterdir()}
        export.write_json(road/'batch-00000.json',dict(directory='batch',outputs=files))
        export.write_json(road/'complete.json',dict(state='COMPLETE',exitCode=0,productionReady=False,approvedMappings=0,checkpoints=['batch-00000.json'],counts={'EXACT_STOP_OCCURRENCE_REVIEW':1},outputs={'batch-00000.json':export.digest(road/'batch-00000.json')}))
        bus=root/'bus.gz';bus.write_bytes(gzip.compress(export.encoded(dict(masterVersion='new',edges=[edge]))))
        return subway,road,bus

    def test_entire_package_build_coverage_and_new_master(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);subway,road,bus=self.fixture(root);out=root/'out'
            export.build(subway,road,bus,out)
            manifest=export.verified(out)
            self.assertEqual(manifest['summary']['subway']['recordCount'],3)
            self.assertEqual(manifest['summary']['road']['EXACT_MATCH_CURRENT_MASTER'],1)
            pkg=out/'road';info=json.loads((pkg/'manifest.json').read_text());detail=export.lookup(pkg,info['exampleReferenceIds'][0],export.digest(pkg/'manifest.json'))
            self.assertEqual(detail['sourceMasterVersion'],'old');self.assertEqual(detail['currentMasterVersion'],'new')
            self.assertFalse(detail['productionReady']);self.assertNotIn('reviewSamples',detail['data'])
            verified=verify.verify(out)
            self.assertEqual(verified['packages']['subway']['verifiedRecords'],3)
            self.assertEqual(verified['packages']['road']['verifiedRecords'],1)
            with self.assertRaises(ValueError):export.build(subway,road,bus,out)


if __name__=='__main__':unittest.main()
