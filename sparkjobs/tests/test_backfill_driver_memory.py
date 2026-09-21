"""대용량 백필이 원본/결과 전체를 driver에 누적하지 않는지 확인한다."""

import ast
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class BackfillDriverMemoryTests(unittest.TestCase):
    def _source(self, name):
        return (ROOT / "batch" / name).read_text(encoding="utf-8")

    def test_topis_backfill_uses_distributed_partition_normalization(self):
        source = self._source("topis_silver_backfill.py")
        ast.parse(source)
        self.assertNotIn(".toLocalIterator(", source)
        self.assertNotIn(".collect(", source)
        self.assertIn("mapPartitions", source)
        self.assertIn("dropDuplicates", source)
        self.assertIn("StorageLevel.DISK_ONLY", source)
        self.assertIn("TOPIS_BACKFILL_PARTITIONS", source)

    def test_incident_backfill_uses_distributed_normalization_and_broadcast_master(self):
        source = self._source("traffic_incident_silver_backfill.py")
        ast.parse(source)
        self.assertNotIn(".toLocalIterator(", source)
        self.assertNotIn(".collect(", source)
        self.assertIn("mapPartitions", source)
        self.assertIn("dropDuplicates", source)
        self.assertIn("StorageLevel.DISK_ONLY", source)
        self.assertIn("spark.sparkContext.broadcast(master)", source)


if __name__ == "__main__":
    unittest.main()
