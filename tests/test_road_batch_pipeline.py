import importlib.util
from pathlib import Path
import tempfile
import unittest


def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).resolve().parents[1]/'infra/scripts'/filename)
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod


batch=load('batch_test','road-batch-pipeline.py')


class RoadBatchTests(unittest.TestCase):
    def road(self,code='0'):
        return dict(linkId='1',parts=[[(0,0),(100,0)]],attributes={'ONEWAY':code,'TRAF_ID_P':'123','TRAF_ID_N':'456','UP_FROM_NO':'1','UP_TO_NODE':'2','DOWN_FROM_':'2','DOWN_TO_NO':'1'})

    def candidate(self,link,start,end,from_node,to_node,along=10):
        return dict(linkId=link,orientation='FORWARD',directedStart=start,directedEnd=end,alongLinkMetres=along,
                    directionEvidence={'sourceNodeFrom':from_node,'sourceNodeTo':to_node})

    def test_reverse_oneway_filtered_before_ranking(self):
        candidate,reason=batch.candidate_at(self.road('1'),(20,0),(-1,0),50)
        self.assertIsNone(candidate);self.assertEqual(reason,'ONEWAY_OPPOSITE_DIRECTION')
        candidate,reason=batch.candidate_at(self.road('0'),(20,0),(-1,0),50)
        self.assertEqual(candidate['alongLinkMetres'],80)
        self.assertEqual(candidate['directionEvidence']['nationalStandardLinkId'],'456')

    def test_multipart_is_explicitly_not_approved(self):
        road=self.road();road['parts'].append([(200,0),(300,0)])
        self.assertEqual(batch.candidate_at(road,(20,0),(1,0),50),(None,'MULTIPART_REQUIRES_REVIEW'))

    def test_connected_occurrence_requires_nodes_and_geometry(self):
        a=self.candidate('a',(0,0),(100,0),'1','2')
        b=self.candidate('b',(100,0),(200,0),'2','3')
        samples=[dict(shapeEdge=0,permittedCandidates=[a]),dict(shapeEdge=1,permittedCandidates=[b])]
        self.assertEqual(batch.occ.connected_path(samples,0,2)['state'],'SINGLE_CONNECTED_CANDIDATE_PATH')
        b['directedStart']=(300,0)
        self.assertEqual(batch.occ.connected_path(samples,0,2)['state'],'DIRECTIONAL_PATH_DISCONNECTED')

    def test_path_gaps_and_ambiguity_remain_visible(self):
        a=self.candidate('a',(0,0),(100,0),'1','2')
        b=self.candidate('b',(0,1),(100,1),'3','4')
        sample=dict(shapeEdge=0,permittedCandidates=[a,b])
        self.assertEqual(batch.occ.connected_path([sample],0,1)['state'],'MULTIPLE_CONNECTED_CANDIDATE_PATHS')
        self.assertEqual(batch.occ.connected_path([sample],0,2)['state'],'SHAPE_SAMPLE_COVERAGE_GAP')
        sample['permittedCandidates']=[]
        self.assertEqual(batch.occ.connected_path([sample],0,1)['state'],'ROAD_CANDIDATE_GAP')

    def test_same_link_cannot_jump_backwards(self):
        a=self.candidate('a',(0,0),(100,0),'1','2',80)
        b=self.candidate('a',(0,0),(100,0),'1','2',10)
        self.assertEqual(batch.occ.connected_path([dict(shapeEdge=0,permittedCandidates=[a]),dict(shapeEdge=1,permittedCandidates=[b])],0,2)['state'],'DIRECTIONAL_PATH_DISCONNECTED')

    def test_checkpoint_damage_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'data').write_text('one')
            manifest=dict(outputs={'data':batch.geo.digest(root/'data')})
            batch.verify(root,manifest)
            (root/'data').write_text('two')
            with self.assertRaises(ValueError):batch.verify(root,manifest)


if __name__=='__main__':unittest.main()
