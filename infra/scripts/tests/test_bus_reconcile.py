import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
import json

spec=importlib.util.spec_from_file_location('reconcile',Path(__file__).parents[1]/'bus-reconcile-source.py')
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class SourceReconcileTests(unittest.TestCase):
    def setUp(self):
        self.source=[dict(ROUTE_ID='r',NODE_ID=s,순번=i,정류소명=s,노선명='route',X좌표=127,Y좌표=37,_row=i+1,_sheet='Data') for i,s in enumerate(('a','b','c'),1)]
        self.row=dict(providerRouteId='r',fromStopId='a',toStopId='b',fromOrder=1,toOrder=2)

    def test_exact_source_and_shift(self):
        index,bad=m.source_index(self.source)
        self.assertFalse(bad)
        self.assertEqual(m.classify(self.row,index)[0],'SOURCE_EXACT_MASTER_GAP')
        self.assertEqual(m.classify(self.row|dict(fromOrder=3,toOrder=4),index)[0],'SOURCE_PAIR_ORDER_DIFFERENCE')

    def test_missing_route_and_edge(self):
        index,_=m.source_index(self.source)
        self.assertEqual(m.classify(self.row|dict(providerRouteId='absent'),index)[0],'SOURCE_ROUTE_MISSING')
        self.assertEqual(m.classify(self.row|dict(toStopId='c'),index)[0],'SOURCE_EDGE_MISSING')

    def test_conflicting_sequence_quarantined(self):
        index,_=m.source_index(self.source+[self.source[0]|dict(NODE_ID='x')])
        self.assertEqual(m.classify(self.row,index)[0],'SOURCE_SEQUENCE_AMBIGUOUS')

    def test_duplicate_row_and_invalid_coordinate(self):
        index,bad=m.source_index(self.source+[self.source[0],self.source[1]|dict(Y좌표=0)])
        self.assertEqual(len(bad),1)
        self.assertEqual(m.classify(self.row,index)[0],'SOURCE_EXACT_MASTER_GAP')

    def test_identifier_preserves_zero_and_rejects_fraction(self):
        self.assertEqual(m.identifier('001'),'001')
        self.assertEqual(m.identifier(100.0),'100')
        for value in (None,True,1.2,float('inf')):
            with self.assertRaises(ValueError): m.identifier(value)

    def test_outputs_remain_candidates_and_can_be_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            source,candidates=root/'source.xlsx',root/'candidates.jsonl'
            source.write_bytes(b'fixture source identity')
            candidates.write_text(json.dumps(self.row|dict(namespace='SEOUL_BUS',reason='ROUTE_NOT_IN_MASTER',observationRows=4))+'\n',encoding='utf-8')
            with patch.object(m,'workbook_records',return_value=iter(self.source)):
                result=m.run(candidates,source,'2026-09-02',root/'out')
            output=Path(result['output'])
            proposal=json.loads((output/'missing-route-proposals.jsonl').read_text(encoding='utf-8'))
            self.assertFalse(proposal['productionReady'])
            self.assertEqual(len(proposal['stops']),3)
            self.assertIn('reused',m.run(candidates,source,'2026-09-02',root/'out'))
            (output/'missing-route-proposals.jsonl').write_text('{}',encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'OUTPUT_CHECKSUM_MISMATCH'):
                m.run(candidates,source,'2026-09-02',root/'out')


if __name__=='__main__': unittest.main()
