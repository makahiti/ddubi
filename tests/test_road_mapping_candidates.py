import importlib.util
import argparse
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1]/'infra/scripts/road-mapping-candidates.py'
spec = importlib.util.spec_from_file_location('road_candidates', SCRIPT)
road = importlib.util.module_from_spec(spec)
spec.loader.exec_module(road)


class RoadCandidatesTests(unittest.TestCase):
    def test_distance_and_heading_forward_reverse(self):
        parts = [[(0,0),(100,0)]]
        self.assertEqual(road.nearest((30,5),(1,0),parts), (5,1,'FORWARD'))
        self.assertEqual(road.nearest((30,5),(-1,0),parts), (5,1,'REVERSE'))

    def test_multipart_gap_is_not_a_road(self):
        result = road.nearest((50,0),(1,0),[[(0,0),(10,0)],[(90,0),(100,0)]])
        self.assertEqual(result[0],40)

    def test_parallel_roads_are_ambiguous(self):
        self.assertEqual(road.classify([{'score':2},{'score':5}],8),'MULTIPLE_PLAUSIBLE_ROADS')
        self.assertEqual(road.classify([{'score':2},{'score':20}],8),'GEOMETRY_CANDIDATE_REQUIRES_REVIEW')
        self.assertEqual(road.classify([],8),'NO_ALIGNED_ROAD_WITHIN_RADIUS')

    def test_samples_cover_long_edges_and_ignore_zero_length(self):
        result = list(road.sample_path([(0,0),(0,0),(100,0)],40))
        self.assertEqual(len(result),3)
        self.assertAlmostEqual(result[-1][1],83.3333333333)
        self.assertTrue(all(r[0]==1 for r in result))

    def test_directional_continuity(self):
        first = dict(id=1,parts=[[(0,0),(100,0)]],orientation='FORWARD')
        second = dict(id=2,parts=[[(100,0),(200,0)]],orientation='FORWARD')
        self.assertEqual(road.topology(first,second),'GEOMETRIC_ENDPOINT_CONTINUITY')
        second['orientation']='REVERSE'
        self.assertEqual(road.topology(first,second),'DISCONNECTED_CANDIDATES')

    def test_duplicate_sequence_and_invalid_coordinates_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'shapes.txt'
            path.write_text('shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n'
                'a,37,127,1\na,37.1,127,2\nb,37,127,1\nb,37.1,127,1\nc,nan,127,1\nc,37,127,2\n')
            shapes, invalid, missing = road.load_shapes(path,{'a','b','c','d'},0)
            self.assertEqual(set(shapes),{'a'})
            self.assertEqual(set(invalid),{'b','c'})
            self.assertEqual(missing,['d'])

    @unittest.skipUnless(importlib.util.find_spec('pyproj') and importlib.util.find_spec('shapefile'), 'optional spatial dependencies')
    def test_end_to_end_output_is_review_only_and_checksum_reusable(self):
        import shapefile
        from pyproj import CRS, Transformer
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = root/'links'
            crs = CRS.from_epsg(5174)
            transform = Transformer.from_crs(4326,crs,always_xy=True,allow_ballpark=False)
            a,b = transform.transform(127,37),transform.transform(127.001,37)
            with shapefile.Writer(str(base),shapeType=3) as writer:
                writer.field('LINK_ID','C',13)
                writer.field('TRAF_ID_P','C',10)
                writer.field('TRAF_ID_N','C',10)
                writer.line([[a,b]])
                writer.record('source-road-1','0','0')
            base.with_suffix('.prj').write_text(crs.to_wkt(),encoding='utf-8')
            archive = root/'roads.zip'
            with zipfile.ZipFile(archive,'w') as z:
                for suffix in ('.shp','.dbf','.shx','.prj'):
                    z.write(base.with_suffix(suffix),'links'+suffix)
            shape = root/'shapes.txt'
            shape.write_text('shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\na,37,127,1\na,37,127.001,2\n')
            args = argparse.Namespace(roads=archive,shapes=shape,output=root/'out',shape_id=[],max_shapes=0,spacing=40,radius=50,ambiguity_margin=8)
            road.run(args)
            report = json.loads((args.output/'report.json').read_text())
            self.assertFalse(report['productionReady'])
            self.assertEqual(report['summary']['approvedMappings'],0)
            self.assertEqual(report['summary']['indexedRoads'],1)
            self.assertFalse(report['providerContract']['equivalenceVerified'])
            self.assertNotIn('mappings', report)
            road.run(args)
            (args.output/'candidates.jsonl').write_text('damaged')
            with self.assertRaises(ValueError):
                road.run(args)


if __name__ == '__main__':
    unittest.main()
