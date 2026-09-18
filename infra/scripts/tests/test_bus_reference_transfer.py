import importlib.util
from pathlib import Path
import tempfile
import unittest
import gzip
import json
from unittest.mock import patch
import io

spec=importlib.util.spec_from_file_location('transfer',Path(__file__).parents[1]/'transfer-bus-reference.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class TransferTest(unittest.TestCase):
    def row(self):
        return dict(masterVersion='v',namespace='SEOUL_BUS',routeId='r',fromStopId='a',toStopId='b',fromOrder=1,toOrder=2,dow=1,hourSlot=1,
            sampleKind='HOURLY_SOURCE_MEAN',meanSeconds=50,sampleCount=1,observedDates=1,
            observationStart='2026-08-30',observationEnd='2026-08-30',sourceSha256='a'*64)
    def test_rejects_cleartext_remote_before_reading_or_network(self):
        with self.assertRaisesRegex(ValueError,'HTTPS_REQUIRED'):m.upload(Path('absent'),'http://example.com','key',Path('absent'))
    def test_checks_real_rows_and_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);raw=gzip.compress(json.dumps(dict(rows=[self.row()])).encode(),mtime=0)
            (root/'000000.json.gz').write_bytes(raw)
            manifest=dict(complete=True,productionReady=False,dataVersion='v',masterVersion='v',chunks=1,rows=1,scannedRows=1,
                sampleKinds={'HOURLY_SOURCE_MEAN':1},outputs={'000000.json.gz':m.digest(raw)})
            (root/'export.json').write_text(json.dumps(manifest),encoding='utf-8')
            self.assertTrue(m.verify(root)['verified'])
            (root/'000000.json.gz').write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError,'CHUNK_CHECKSUM'):m.verify(root)
    def test_period_and_occurrence_guards(self):
        for patch in [dict(observedDates=2,sampleCount=2),dict(toOrder=3),dict(meanSeconds=float('nan')),dict(dow=True)]:
            with self.assertRaises(ValueError):m.validate_row(self.row()|patch)

    def test_upload_resume_requires_server_totals_and_same_target(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); raw=gzip.compress(json.dumps(dict(rows=[self.row()])).encode(),mtime=0)
            (root/'000000.json.gz').write_bytes(raw)
            manifest=dict(complete=True,productionReady=False,dataVersion='v',masterVersion='v',chunks=1,rows=1,scannedRows=1,
                sampleKinds={'HOURLY_SOURCE_MEAN':1},outputs={'000000.json.gz':m.digest(raw)})
            (root/'export.json').write_text(json.dumps(manifest),encoding='utf-8')
            checkpoint=root/'checkpoint.json'
            calls=[]
            class Opener:
                total=1
                def open(self,request,timeout):
                    calls.append(request.method)
                    data=(dict(receivedRows=1,productionReady=False) if request.method=='POST' else
                          dict(dataVersion='v',totalRows=self.total,totalChunks=1,productionReady=False))
                    return io.BytesIO(json.dumps(dict(success=True,data=data)).encode())
            opener=Opener()
            with patch.object(m.urllib.request,'build_opener',return_value=opener):
                self.assertTrue(m.upload(root,'http://127.0.0.1:8080','test-key',checkpoint)['serverTotalsVerified'])
                self.assertEqual(calls,['POST','GET'])
                calls.clear()
                m.upload(root,'http://127.0.0.1:8080','test-key',checkpoint)
                self.assertEqual(calls,['GET'])
                self.assertNotIn('test-key',checkpoint.read_text())
                opener.total=0
                with self.assertRaisesRegex(ValueError,'SERVER_TOTALS_MISMATCH'):
                    m.upload(root,'http://127.0.0.1:8080','test-key',checkpoint)
                with self.assertRaisesRegex(ValueError,'CHECKPOINT_TARGET_MISMATCH'):
                    m.upload(root,'http://localhost:8080','test-key',checkpoint)

if __name__=='__main__':unittest.main()
