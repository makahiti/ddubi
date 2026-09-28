import json
import tempfile
import unittest
from pathlib import Path

from verify_link_api import candidate_link_ids, parse_vertex_xml, read_env_value


class VerifyLinkApiTest(unittest.TestCase):
    def test_env_reader_does_not_need_process_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("OTHER=x\nTRAFFIC_INCIDENT_API_KEY='secret'\n", encoding="utf-8")
            self.assertEqual(read_env_value(path, "TRAFFIC_INCIDENT_API_KEY"), "secret")

    def test_portal_key_can_be_read_from_env_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("SEOUL_OPEN_DATA_API_KEY='shared-secret'\n", encoding="utf-8")
            self.assertEqual(read_env_value(path, "SEOUL_OPEN_DATA_API_KEY"), "shared-secret")

    def test_candidate_ids_are_unique_and_sorted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidates.jsonl"
            rows = [{"topisServiceLinkIds": ["2", "1"]}, {"topisServiceLinkIds": ["2"]}]
            path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            self.assertEqual(candidate_link_ids(path), ["1", "2"])

    def test_vertex_xml_is_transformed_to_wgs84(self):
        raw = b"""<LinkVerInfo><RESULT><CODE>INFO-000</CODE><MESSAGE>ok</MESSAGE></RESULT>
        <row><link_id>1220003800</link_id><ver_seq>1</ver_seq><grs80tm_x>205578</grs80tm_x><grs80tm_y>446923</grs80tm_y></row>
        <row><link_id>1220003800</link_id><ver_seq>2</ver_seq><grs80tm_x>205590</grs80tm_x><grs80tm_y>446895</grs80tm_y></row></LinkVerInfo>"""
        result = parse_vertex_xml(raw, "1220003800")
        self.assertEqual(result["vertexCount"], 2)
        longitude, latitude = result["geometry"][0]
        self.assertTrue(126.0 <= longitude <= 128.0)
        self.assertTrue(37.0 <= latitude <= 38.0)

    def test_mismatched_link_is_rejected(self):
        raw = b"""<LinkVerInfo><RESULT><CODE>INFO-000</CODE></RESULT>
        <row><link_id>wrong</link_id><ver_seq>1</ver_seq><grs80tm_x>205578</grs80tm_x><grs80tm_y>446923</grs80tm_y></row></LinkVerInfo>"""
        with self.assertRaisesRegex(ValueError, "does not match"):
            parse_vertex_xml(raw, "1220003800")


if __name__ == "__main__":
    unittest.main()
