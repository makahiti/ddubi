import csv
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('prep', Path(__file__).parents[1] / 'prepare-local-transit.py')
prep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prep)


class LocalTransitTests(unittest.TestCase):
    def test_actual_gap_duplicate_and_conflict(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'source.csv'
            header = ['기준일','노선ID','운행계획ID','운행순번','정류장순번','정류장ID','도착일시','출발일시']
            a = ['20260901','R','1','1','1','A','20260901080000','20260901080010']
            b = ['20260901','R','1','1','2','B','20260901080110','20260901080120']
            c = ['20260901','R','1','1','4','C','20260901080300','20260901080310']
            with source.open('w', encoding='utf-8-sig', newline='') as f:
                w = csv.writer(f); w.writerow(header); w.writerows([a,a,b,c])
            result = prep.traversal(source, root)
            self.assertEqual(result['candidateTraversals'], 1)
            self.assertEqual(result['reasons']['EXACT_DUPLICATE'], 1)
            self.assertEqual(result['reasons']['MISSING_ADJACENT_SEQUENCE'], 1)
            record = json.loads((root/'traversals.jsonl').read_text(encoding='utf-8'))
            self.assertEqual(record['durationSeconds'],60)
            self.assertIsNone(record['weatherBucket'])
            self.assertFalse(record['productionReady'])
            with source.open('a', encoding='utf-8', newline='') as f:
                b[5] = 'X'; csv.writer(f).writerow(b)
            self.assertEqual(prep.traversal(source,root)['quarantinedTrips'],1)

    def test_hourly_zero_conflicts_and_semantics(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); source=root/'input.zip'
            data=io.StringIO(newline=''); w=csv.writer(data)
            w.writerow(['기준_날짜','노선_ID','출발_정류장_ID','도착_정류장_ID','출발_정류장_순서','도착_정류장_순서','운행시간_00시','운행시간_01시'])
            w.writerows([['20260901','R','A','B','1','2','60','0'],['20260901','R','A','B','1','2','60','0'],['20260902','R','A','B','1','2','90',''],['20260902','R','A','B','1','2','100','']])
            with zipfile.ZipFile(source,'w') as z: z.writestr('source.csv',data.getvalue().encode('cp949'))
            result=prep.hourly(source,root)
            self.assertEqual(result['distinctDailyKeys'],1)
            self.assertEqual(result['conflictingDailyKeys'],1)
            record=json.loads((root/'hourly-reference.jsonl').read_text(encoding='utf-8'))
            self.assertEqual(record['meanOfHourlyValuesSeconds'],60)
            self.assertEqual(record['observedDates'],1)
            self.assertNotIn('p95',record)


if __name__=='__main__': unittest.main()
