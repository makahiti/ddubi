"""누락 정류장을 붙여 가짜 인접 구간을 만들거나 기존 공식 ID열을 조용히 바꾸지 않는지 검사한다."""
import unittest
from update_seoul_bus_master import supplement
from bus_crosswalk_artifact import build_crosswalk


class UpdateTest(unittest.TestCase):
    def row(self, seq, sid):
        return (100,'100',seq,sid,'00123','정류장',127.0,37.5)

    def test_gaps_are_preserved_and_never_become_adjacent_edges(self):
        base={'routes':[],'version':'old','reviews':[]}
        updated,report=supplement(base,[self.row(1,101),self.row(3,103),self.row(4,104)],'new')
        edges=build_crosswalk(updated,'a'*64)['edges']
        self.assertEqual(len(edges),1)
        self.assertEqual(edges[0]['fromOrder'],3)
        self.assertEqual(report['addedRoutes'][0]['missingSequences'],[2])
        self.assertEqual(base['routes'],[])

    def test_duplicate_sequence_and_changed_existing_topology_fail_closed(self):
        with self.assertRaises(ValueError):supplement({'routes':[]},[self.row(1,101),self.row(1,102)],'new')
        base,_=supplement({'routes':[]},[self.row(1,101),self.row(2,102)],'first')
        with self.assertRaises(ValueError):supplement(base,[self.row(1,101),self.row(2,199)],'new')


if __name__=='__main__':unittest.main()
