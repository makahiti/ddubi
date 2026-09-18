import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('bundle', Path(__file__).parents[1] / 'verify-transit-bundle.py')
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class BundleTests(unittest.TestCase):
    def test_incomplete(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertFalse(bundle.verify(Path(root))['integrityVerified'])

    def test_integrity_is_not_approval_and_tamper_fails(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            artifact = root / 'data.jsonl'
            artifact.write_text('{}', encoding='utf-8')
            manifest = dict(sourceSha256='a'*64, codeSha256='b'*64,
                            productionReady=True, outputs={artifact.name:bundle.digest(artifact)})
            (root/'complete.json').write_text(json.dumps(manifest),encoding='utf-8')
            result = bundle.verify(root)
            self.assertTrue(result['integrityVerified'])
            self.assertFalse(result['productionApproved'])
            artifact.write_text('changed',encoding='utf-8')
            self.assertFalse(bundle.verify(root)['integrityVerified'])

    def test_path_escape(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root/'complete.json').write_text(json.dumps(dict(sourceSha256='a'*64,
                codeSha256='b'*64, outputs={'../outside':'a'*64})),encoding='utf-8')
            self.assertIn('OUTPUT_PATH_ESCAPE',bundle.verify(root)['failures'])

    def test_part_provenance_formats(self):
        for manifest in [dict(inputs={'raw':'a'*64},codeSha256='b'*64),
                         dict(inputs={'raw':'a'*64},codeSha256={'build.py':'b'*64}),
                         dict(inputs={'sha256':{'raw':'a'*64,'build.py':'b'*64},'radius':300}),
                         dict(inputs={'raw':'a'*64,'build.py':'b'*64})]:
            self.assertEqual(bundle.provenance_failures(manifest),[])
        self.assertTrue(bundle.provenance_failures(dict(inputs={'raw':'a'*64})))
        self.assertTrue(bundle.provenance_failures(dict(inputs={'sha256':{}})))

    def test_invalid_manifest_and_output_hash(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root)
            manifest=root/'complete.json'
            manifest.write_text('[]',encoding='utf-8')
            self.assertFalse(bundle.verify(root)['integrityVerified'])
            manifest.write_text(json.dumps(dict(sourceSha256='a'*64,codeSha256='b'*64,
                outputs={'data':'bad'})),encoding='utf-8')
            self.assertIn('INVALID_OUTPUT_ENTRY',bundle.verify(root)['failures'])


if __name__=='__main__': unittest.main()
