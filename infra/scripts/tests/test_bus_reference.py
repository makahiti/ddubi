import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('reference',Path(__file__).parents[1]/'bus-build-reference.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class ReferenceTests(unittest.TestCase):
    def source(self,path,rows):
        c=sqlite3.connect(path)
        c.execute('CREATE TABLE daily(day TEXT,route TEXT,a TEXT,b TEXT,ao TEXT,bo TEXT,hour INTEGER,seconds REAL,conflict INTEGER)')
        c.executemany('INSERT INTO daily VALUES(?,?,?,?,?,?,?,?,?)',rows);c.commit();c.close()

    def test_date_overlap_boundaries(self):
        self.assertTrue(m.overlaps([('2026-06-01','2026-07-01'),('2026-07-01','2026-07-04')]))
        self.assertFalse(m.overlaps([('2026-06-01','2026-07-01'),('2026-08-01','2026-08-04')]))

    def test_duplicate_conflicts_and_unmapped_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            base=('2026-08-17','r','a','b','1','2',8)
            self.source(root/'a.db',[(*base,100,0),(*base[:6],9,200,0),(*base[:6],10,90,1)])
            self.source(root/'b.db',[(*base,100,0),(*base[:6],9,201,0),(*base[:6],10,90,0),('2026-08-17','x','a','b','1','2',8,50,0)])
            db=m.connect(root/'out.db')
            m.attach(db,root/'a.db','a');m.attach(db,root/'b.db','b')
            edge=dict(namespace='SEOUL_BUS',routeId='r',fromStopId='a',toStopId='b',fromOrder=1,toOrder=2,segmentId='BUS:r:a:b',direction=None)
            m.mapping_table(db,dict(edges=[edge]))
            result=m.aggregate(db,['a','b'],True)
            self.assertEqual(result,dict(mappedDailyKeys=1,mappedGroups=1,unmappedDailyKeys=1))
            self.assertEqual(db.execute('SELECT totalSeconds,observedDates,dow FROM hourly_reference').fetchone(),(100,1,2))
            db.close()

    def test_actual_dedup_and_readiness(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'actual.jsonl'
            row=dict(sourceEventId='event',mappingStatus='EXACT_ID_ORDER_CANDIDATE',enteredAt='2026-08-30T08:00:00+09:00',canonicalSegmentId='BUS:r:a:b',fromOrder=1,toOrder=2,durationSeconds=60)
            source.write_text(json.dumps(row)+'\n'+json.dumps(row)+'\n',encoding='utf-8')
            result=m.actual_reference(source,root,2,2)
            self.assertEqual(result['duplicateEvents'],1)
            output=json.loads((root/'actual-reference.jsonl').read_text(encoding='utf-8'))
            self.assertEqual(output['dow'],1)
            self.assertFalse(output['sampleSufficient'])
            self.assertFalse(output['productionReady'])
            self.assertIsNone(output['weatherBucket'])


class ProposalRefreshTest(unittest.TestCase):
    def test_existing_route_is_not_added_or_approved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=root/'proposals.jsonl'
            source.write_text(json.dumps(dict(namespace='SEOUL_BUS',providerRouteId='r'))+'\n',encoding='utf-8')
            result=m.route_proposals(source,root,'new',[dict(namespace='SEOUL_BUS',routeId='r')])
            self.assertEqual(result,dict(routes=0,stops=0,alreadyPresent=1))
            output=json.loads((root/'master-additions-candidate.json').read_text(encoding='utf-8'))
            self.assertFalse(output['productionReady'])
            self.assertEqual(output['alreadyPresent'][0]['status'],'ALREADY_IN_MASTER_REQUIRES_EDGE_REVIEW')


if __name__=='__main__': unittest.main()
