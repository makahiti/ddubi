import collections
import importlib.util
from pathlib import Path
import unittest


def module(filename,name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).resolve().parents[1]/'infra/scripts'/filename)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


road=module('road-occurrence-review.py','occurrence')
manual=module('road-source-manual.py','manual')


class OccurrenceTests(unittest.TestCase):
    def test_oneway_reverse_rejected_and_zero_not_link(self):
        candidate=dict(orientation='REVERSE',attributes={'ONEWAY':'1','TRAF_ID_N':'123'})
        self.assertEqual(road.direction_evidence(candidate)['state'],'ONEWAY_OPPOSITE_DIRECTION')
        candidate['orientation']='FORWARD';candidate['attributes']['TRAF_ID_P']='0'
        result=road.direction_evidence(candidate)
        self.assertTrue(result['permitted']);self.assertIsNone(result['nationalStandardLinkId'])
        self.assertIsNone(result['runtimeLinkNamespace']);self.assertFalse(result['runtimeNamespaceVerified'])

    def test_unknown_direction_not_permitted(self):
        self.assertFalse(road.direction_evidence(dict(orientation='FORWARD',attributes={'ONEWAY':'9'}))['permitted'])

    def test_reverse_uses_reverse_nodes_and_link(self):
        candidate=dict(orientation='REVERSE',attributes={'ONEWAY':'0','TRAF_ID_P':'111','TRAF_ID_N':'222','DOWN_FROM_':'9','DOWN_TO_NO':'8'})
        result=road.direction_evidence(candidate)
        self.assertEqual((result['nationalStandardLinkId'],result['sourceNodeFrom'],result['sourceNodeTo']),('222','9','8'))

    def test_occurrence_keeps_loop_order_and_namespace(self):
        first=dict(stop_id='SB_1',stop_sequence='4')
        last=dict(stop_id='SB_2',stop_sequence='5')
        index={('SEOUL_BUS','99',4,5,'1','2'):[{'segmentId':'known'}]}
        self.assertEqual(road.exact_edge(index,'SEOUL_BUS','SB_99','SB_',first,last)['segmentId'],'known')
        first['stop_sequence']='1'
        with self.assertRaises(ValueError):road.exact_edge(index,'SEOUL_BUS','SB_99','SB_',first,last)
        with self.assertRaises(ValueError):road.exact_edge(index,'GBIS','SB_99','SB_',first,last)

    def test_stop_shape_distance_and_coordinates_both_required(self):
        shape=[dict(shape_dist_traveled='0',shape_pt_lat='37',shape_pt_lon='127'),dict(shape_dist_traveled='100',shape_pt_lat='37.001',shape_pt_lon='127')]
        stop=dict(stop_id='SB_1',shape_dist_traveled='100')
        coordinates={'SB_1':dict(stop_lat='37.001',stop_lon='127')}
        self.assertEqual(road.vertex_at_stop(stop,shape,coordinates),1)
        coordinates['SB_1']['stop_lat']='38'
        with self.assertRaises(ValueError):road.vertex_at_stop(stop,shape,coordinates)
        coordinates['SB_1']['stop_lat']='nan'
        with self.assertRaises(ValueError):road.vertex_at_stop(stop,shape,coordinates)

    def test_hwp_record_decode_and_truncation(self):
        import struct,zlib
        raw='ONEWAY 일방통행'.encode('utf-16-le')
        data=struct.pack('<I',(len(raw)<<20)|67)+raw
        self.assertEqual(list(manual.paragraphs(data,False)),['ONEWAY 일방통행'])
        compress=zlib.compressobj(wbits=-15)
        self.assertEqual(list(manual.paragraphs(compress.compress(data)+compress.flush(),True)),['ONEWAY 일방통행'])
        with self.assertRaises(ValueError):list(manual.paragraphs(data[:-1],False))


if __name__=='__main__':unittest.main()
