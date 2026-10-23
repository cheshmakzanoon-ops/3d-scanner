"""Evaluator guards. Numerical tolerances are test tolerances, not reconstruction quality gates."""
import json, unittest
from pathlib import Path
import numpy as np
from evaluate import raster, evaluate, observable_convex, normals, project

class Guards(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.m = json.loads((Path(__file__).parent / 'fixture/fixture.json').read_text())
        cls.v = np.asarray(cls.m['vertices'])
        cls.t = np.asarray(cls.m['triangles'])

    def test_identity(self):
        r = evaluate(self.m, self.v, self.t)
        self.assertEqual(r['projectedPixelCoverage'], 1.0)
        self.assertLess(r['referenceSurfaceSampling']['referenceToCandidateDistance']['max'], 1e-12)
        self.assertGreater(r['referenceSurfaceSampling']['unknownExcludedSamples'], 0)

    def test_empty_preserves_unknown(self):
        r = evaluate(self.m, np.empty((0, 3)), np.empty((0, 3), int))
        self.assertEqual(r['projectedPixelCoverage'], 0.0)
        self.assertEqual(r['referenceSurfaceSampling']['referenceToCandidateDistance']['finiteCount'], 0)
        self.assertEqual(r['referenceSurfaceSampling']['distanceCDF']['0.01'], 0.0)

    def test_displacement_not_aligned_away(self):
        r = evaluate(self.m, self.v + np.array([20, 0, 0]), self.t)
        self.assertEqual(r['projectedPixelCoverage'], 0.0)
        self.assertGreater(r['referenceSurfaceSampling']['referenceToCandidateDistance']['p50'], 15)

    def test_occluded_backfaces_excluded(self):
        ns = normals(self.v, self.t)
        centers = self.v[self.t].mean(axis=1)
        count = observable_convex(centers, ns, self.m['cameras'], 160, 120)
        self.assertTrue(np.all(count[(ns[:, 1] > 0.9) | (ns[:, 2] < -0.9)] == 0))
        self.assertTrue(np.all(count[ns[:, 1] < -0.9] >= 2))

    def test_independent_blender_raycast(self):
        buffers = [raster(self.v, self.t, c, 160, 120)[0] for c in self.m['cameras']]
        for witness in self.m['rayWitness']:
            z = buffers[witness['view']][witness['y'], witness['x']]
            if witness['cameraZ'] is None:
                self.assertFalse(np.isfinite(z))
            else:
                self.assertAlmostEqual(z, witness['cameraZ'], delta=2e-06)

    def test_partial_reference_fov_rejected(self):
        import copy
        m = copy.deepcopy(self.m)
        m['cameras'][0][12] = 1000.0
        with self.assertRaisesRegex(ValueError, 'full-face visibility proof'):
            evaluate(m, self.v, self.t)

    def test_malformed_candidate_rejected(self):
        for v, t in [(self.v * np.nan, self.t), (self.v, np.array([[0, 1, 999]]))]:
            with self.assertRaises(ValueError):
                evaluate(self.m, v, t)

    def test_nonpositive_candidate_triangle_reported(self):
        c = np.asarray(self.m['cameras'][0])
        center = -c[:9].reshape(3, 3).T @ c[9:12]
        behind = np.tile(center, (3, 1)) - c[:9].reshape(3, 3)[2] + np.array([[0, 0, 0], [0.1, 0, 0], [0, 0.1, 0]])
        r = evaluate(self.m, behind, np.array([[0, 1, 2]]))
        self.assertEqual(r['perView'][0]['candidateTrianglesSkippedNonpositiveCameraZ'], 1)

    def test_invalid_reference_and_camera_rejected(self):
        import copy
        for kind in ['geometry', 'intrinsics', 'nonfinite']:
            m = copy.deepcopy(self.m)
            if kind == 'geometry':
                m['vertices'][0][0] = 100
            elif kind == 'intrinsics':
                m['cameras'][0][12] = 0
            else:
                m['cameras'][0][9] = float('nan')
            with self.assertRaises(ValueError):
                evaluate(m, self.v, self.t)

    def test_camera_projection(self):
        for c in self.m['cameras']:
            c = np.asarray(c)
            r = c[:9].reshape(3, 3)
            np.testing.assert_allclose(r @ r.T, np.eye(3), atol=1e-06)
            uv, z = project(np.zeros((1, 3)), c)
            np.testing.assert_allclose(uv[0], [79.5, 59.5], atol=0.0001)
            self.assertGreater(z[0], 0)

    def test_zbuffer_nearer_surface(self):
        c = [1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 10, 10, 10, 10]
        v = np.array([[-1, -1, 2], [1, -1, 2], [0, 1, 2], [-2, -2, 4], [2, -2, 4], [0, 2, 4]])
        d, f = raster(v, np.array([[3, 4, 5], [0, 1, 2]]), c, 21, 21)
        self.assertEqual(d[10, 10], 2)
        self.assertEqual(f[10, 10], 1)
if __name__ == '__main__':
    unittest.main()
