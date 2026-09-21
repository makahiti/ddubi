"""GTFS 계산 완료값의 gzip 계약과 보수적 ID 검증을 확인한다."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def module(name):
    path=Path(__file__).parents[1]/name
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value)
    return value


exporter=module("export-gtfs-bus-formula.py")
transfer=module("transfer-bus-reference.py")


class GtfsFormulaExportTest(unittest.TestCase):
    def row(self):
        return dict(namespace="GBIS",routeId="GBIS.1",fromStopId="GBIS.10",toStopId="GBIS.11",
                    fromOrder=1,toOrder=2,hourSlot=8,meanSeconds=71.25,sampleCount=5)

    def test_export_is_accepted_by_existing_gzip_transfer_contract(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/"mapped.jsonl";source.write_text(json.dumps(self.row())+"\n",encoding="utf-8")
            out=root/"out";result=exporter.run(source,out,"master-v1","2025-03-01","2030-12-31")
            verified=transfer.verify(out)
            self.assertEqual(1,result["rows"]);self.assertEqual(1,verified["rows"])
            self.assertEqual({"GTFS_SCHEDULE_FORMULA":1},verified["sampleKinds"])

    def test_raw_provider_id_cannot_bypass_project_mapping(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/"mapped.jsonl";row=self.row();row["routeId"]="1"
            source.write_text(json.dumps(row)+"\n",encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"NON_CANONICAL_ID"):
                exporter.run(source,root/"out","master-v1","2025-03-01","2030-12-31")


if __name__=="__main__": unittest.main()
