"""Parser/provenance negative tests only: no JVM/native execution or downloads."""
import contextlib
import io
import tempfile
from pathlib import Path
import unittest
import importlib.util

VALIDATOR_PATH = Path(__file__).resolve().parents[1] / "tools/native_wrapper_validation/validate.py"
SPEC = importlib.util.spec_from_file_location("native_wrapper_validate", VALIDATOR_PATH)
validate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validate)


class HelperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.repo = self.root / 'repo'
        (self.repo / 'reconstruction/src').mkdir(parents=True)
        (self.repo / 'reconstruction/include').mkdir()
        (self.repo / 'CMakeLists.txt').write_text('synthetic cmake')
        (self.repo / 'reconstruction/src/jni.cpp').write_text('synthetic source')
        self.engine = self.root / 'engine.so'
        self.shim = self.root / 'shim.so'
        self.engine.write_bytes(b'not a real ELF')
        self.shim.write_bytes(b'not a real JNI binary')
        self.manifest = validate.observed_manifest(self.repo, self.engine, self.shim)

    def tearDown(self):
        self.tmp.cleanup()

    def check(self):
        validate.check_manifest(self.manifest, self.repo, self.engine, self.shim)

    def test_required_inputs_and_invalid_mode_rejected(self):
        for args in [[], ['run'], ['download']]:
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                validate.parser().parse_args(args)

    def test_manifest_is_explicit_observation_not_build_proof(self):
        self.check()
        self.assertIn('do NOT prove', self.manifest['provenance']['binarySourceRelationship'])

    def test_artifact_drift_rejected(self):
        self.shim.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'artifact hash'):
            self.check()

    def test_source_closure_drift_rejected(self):
        (self.repo / 'reconstruction/include/new.hpp').write_text('new input')
        with self.assertRaisesRegex(ValueError, 'source closure'):
            self.check()

    def test_missing_provenance_rejected(self):
        del self.manifest['provenance']
        with self.assertRaisesRegex(ValueError, 'provenance'):
            self.check()

    def test_schema_mismatch_rejected(self):
        self.manifest['schema'] = 2
        with self.assertRaisesRegex(ValueError, 'schema'):
            self.check()

    def test_tree_binds_classes_outside_engine_and_metadata(self):
        app = self.root / 'classes'
        (app / 'outside').mkdir(parents=True)
        (app / 'outside/Dependency.class').write_bytes(b'class')
        (app / 'metadata.kotlin_module').write_bytes(b'metadata')
        hashes = validate.tree_hashes(app)
        self.assertEqual({'outside/Dependency.class', 'metadata.kotlin_module'}, set(hashes))
        (app / 'outside/Dependency.class').write_bytes(b'changed')
        self.assertNotEqual(hashes, validate.tree_hashes(app))

    def test_symlink_and_empty_tree_rejected(self):
        app = self.root / 'classes'
        app.mkdir()
        with self.assertRaisesRegex(ValueError, 'Empty'):
            validate.tree_hashes(app)
        (app / 'bad.class').symlink_to(self.engine)
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            validate.tree_hashes(app)

    def test_success_receipt_invalidation_preserves_prior_evidence(self):
        receipt = self.root / 'run-receipt.json'
        validate.atomic_json(receipt, {'prior': 'pass'})
        original = receipt.read_bytes()
        validate.invalidate_receipt(receipt)
        self.assertFalse(receipt.exists())
        old = list(self.root.glob('superseded-*-run-receipt.json'))
        self.assertEqual(1, len(old))
        self.assertEqual(original, old[0].read_bytes())


if __name__ == '__main__':
    unittest.main()
