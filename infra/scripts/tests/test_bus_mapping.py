import importlib.util
from pathlib import Path
import unittest
import gzip
import json
import tempfile

spec = importlib.util.spec_from_file_location('mapping', Path(__file__).parents[1] / 'bus-map-candidates.py')
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


class MappingTests(unittest.TestCase):
    def setUp(self):
        self.edge = dict(namespace='SEOUL_BUS', routeId='r', fromStopId='a', toStopId='b', fromOrder=1, toOrder=2, segmentId='BUS:r:a:b', direction=None)
        self.row = dict(providerRouteId='r', fromStopId='a', toStopId='b', fromOrder=1, toOrder=2)

    def mapper(self, extra=()):
        return mapping.Mapper({'edges':[self.edge, *extra]}, 'SEOUL_BUS')

    def test_exact_and_duplicate(self):
        status, edges = self.mapper([self.edge.copy()]).match(self.row)
        self.assertEqual(status, 'EXACT_ID_ORDER_CANDIDATE')
        self.assertEqual(len(edges), 1)

    def test_namespace_collision_ignored(self):
        other = self.edge | dict(namespace='GBIS', segmentId='other')
        self.assertEqual(self.mapper([other]).match(self.row)[0], 'EXACT_ID_ORDER_CANDIDATE')

    def test_order_mismatch_not_silently_accepted(self):
        self.assertEqual(self.mapper().match(self.row | dict(fromOrder=4,toOrder=5))[0], 'ORDER_MISMATCH')

    def test_unknown_route_and_edge(self):
        self.assertEqual(self.mapper().match(self.row | dict(providerRouteId='other'))[0], 'ROUTE_NOT_IN_MASTER')
        self.assertEqual(self.mapper().match(self.row | dict(toStopId='other'))[0], 'EDGE_NOT_IN_MASTER')

    def test_ambiguous_direction(self):
        self.assertEqual(self.mapper([self.edge | dict(direction='UP')]).match(self.row)[0], 'AMBIGUOUS_MASTER')

    def test_nonadjacent_and_invalid_order(self):
        for delta in (dict(toOrder=3), dict(fromOrder=1.5), dict(fromOrder=True),dict(fromStopId=None)):
            self.assertEqual(self.mapper().match(self.row | delta)[0], 'INVALID_INPUT')

    def test_manifest_reuse_tamper_and_master_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            master, crosswalk, source = [root / n for n in ('master.gz','crosswalk.gz','input.jsonl')]
            with gzip.open(master, 'wt', encoding='utf-8') as f:
                json.dump({'version':'v1'}, f)
            data = dict(masterVersion='v1',masterSha256=mapping.sha(master),edges=[self.edge])
            with gzip.open(crosswalk, 'wt', encoding='utf-8') as f:
                json.dump(data, f)
            source.write_text(json.dumps(self.row | dict(productionReady=True, weatherBucket=None))+'\n',encoding='utf-8')
            args = (source,crosswalk,master,'SEOUL_BUS',root/'out')
            result = mapping.run(*args)
            output = Path(result['output']) / 'mapped-candidates.jsonl'
            row = json.loads(output.read_text(encoding='utf-8'))
            self.assertFalse(row['productionReady'])
            self.assertIsNone(row['weatherBucket'])
            self.assertIn('reused',mapping.run(*args))
            output.write_text('tampered',encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'OUTPUT_CHECKSUM_MISMATCH'):
                mapping.run(*args)
            with gzip.open(master, 'wt', encoding='utf-8') as f:
                json.dump({'version':'v2'},f)
            with self.assertRaisesRegex(ValueError,'CROSSWALK_MASTER_MISMATCH'):
                mapping.run(*args)


if __name__ == '__main__':
    unittest.main()
