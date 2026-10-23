import json, os, tempfile, unittest
from pathlib import Path
import inputs

# Explicit fixture is convenient for external source review. Published checkout default
# uses the committed path, never an unpublished machine-specific directory.
FIXTURE = Path(os.environ.get('RF_EIGHT_FIXTURE',
    'docs/development/experiments/new-cuboid-eight-view-160x120'))
class BoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)/'observations'
        inputs.stage(FIXTURE,self.root)
    def tearDown(self): self.tmp.cleanup()
    def reject(self):
        with self.assertRaises((ValueError, OSError, KeyError, TypeError)):
            inputs.validate(self.root)
    def mutate(self,fn):
        p=self.root/'observation-inputs.json';m=json.loads(p.read_text());fn(m);p.write_text(json.dumps(m))
    def test_exact_inputs(self): self.assertEqual(8,len(inputs.validate(self.root)['images']))
    def test_truth_directory_rejected(self): (self.root/'evaluation-only').mkdir();self.reject()
    def test_pose_injection_rejected(self): self.mutate(lambda m:m['images'][0].update(rotation=[1]*9));self.reject()
    def test_near_far_rejected(self): self.mutate(lambda m:m.update(near=.1,far=10));self.reject()
    def test_mask_rejected(self): (self.root/'images'/'mask.png').write_bytes(b'x');self.reject()
    def test_missing_view_rejected(self): (self.root/'images/view-07.png').unlink();self.reject()
    def test_changed_intrinsics_rejected(self): self.mutate(lambda m:m['images'][0]['K'][0].__setitem__(0,201));self.reject()
    def test_traversal_rejected(self): self.mutate(lambda m:m['images'][0].update(path='../evaluation-only/fixture.json'));self.reject()
    def test_reordered_views_rejected(self): self.mutate(lambda m:m['images'].reverse());self.reject()
    def test_modified_bytes_rejected(self): p=self.root/'images/view-00.png';p.write_bytes(p.read_bytes()+b'x');self.reject()
    def test_symlink_rejected(self):
        p=self.root/'images/view-00.png';p.unlink();p.symlink_to(FIXTURE/'images/view-00.png');self.reject()
    def test_duplicate_json_rejected(self):
        p=self.root/'observation-inputs.json';p.write_text(p.read_text().replace('"schema": 1','"schema": 1, "schema": 1'));self.reject()
    def test_source_truth_irrelevant_to_staging(self):
        # Construct source from staged observations only; arbitrary truth must never be opened/copied.
        (self.root/'evaluation-only').mkdir();(self.root/'evaluation-only/fixture.json').write_text('invalid truth')
        a=inputs.stage(self.root,Path(self.tmp.name)/'a')
        (self.root/'evaluation-only/fixture.json').write_text('different invalid truth')
        b=inputs.stage(self.root,Path(self.tmp.name)/'b')
        self.assertEqual(a,b)
        self.assertFalse((Path(self.tmp.name)/'a/evaluation-only').exists())
    def test_no_truth_required(self):
        self.assertEqual(inputs.validate(self.root),inputs.stage(self.root,Path(self.tmp.name)/'copy'))
if __name__=='__main__':unittest.main()
