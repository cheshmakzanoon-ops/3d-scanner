"""Truth-only post-hoc evaluator. No mesh repair, ICP, welding, or reconstruction calls."""
import numpy as np
from trimesh.triangles import closest_point

def project(points, camera):
    c = np.asarray(camera)
    p = np.asarray(points) @ c[:9].reshape(3, 3).T + c[9:12]
    return (p[:, :2] / p[:, 2, None] * c[12:14] + c[14:16], p[:, 2])

def raster(vertices, triangles, camera, width, height):
    uv, z = project(vertices, camera)
    depth = np.full((height, width), np.inf)
    face = np.full((height, width), -1, dtype=int)
    for index, ids in enumerate(triangles):
        a, b, c = uv[ids]
        zz = z[ids]
        if not np.all(zz > 0):
            continue
        lo = np.maximum(0, np.ceil(np.min([a, b, c], axis=0)).astype(int))
        hi = np.minimum([width - 1, height - 1], np.floor(np.max([a, b, c], axis=0)).astype(int))
        if np.any(hi < lo):
            continue
        den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(den) < 1e-12:
            continue
        x, y = np.meshgrid(np.arange(lo[0], hi[0] + 1), np.arange(lo[1], hi[1] + 1))
        u = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / den
        v = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / den
        w = 1 - u - v
        inv = u / zz[0] + v / zz[1] + w / zz[2]
        candidate = np.full(inv.shape, np.inf)
        np.divide(1.0, inv, out=candidate, where=(u >= -1e-12) & (v >= -1e-12) & (w >= -1e-12) & (inv > 0))
        target = depth[lo[1]:hi[1] + 1, lo[0]:hi[0] + 1]
        f = face[lo[1]:hi[1] + 1, lo[0]:hi[0] + 1]
        closer = candidate < target
        f[closer] = index
        np.minimum(target, candidate, out=target)
    return (depth, face)

def normals(vertices, triangles):
    t = vertices[triangles]
    n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
    return n / np.linalg.norm(n, axis=1)[:, None]

def observable_convex(points, normals_, cameras, width, height):
    """Exact front-face visibility ONLY for this convex cuboid; includes 6px kernel border."""
    count = np.zeros(len(points), int)
    for c in cameras:
        c = np.asarray(c)
        center = -c[:9].reshape(3, 3).T @ c[9:12]
        uv, z = project(points, c)
        count += (np.einsum('ij,ij->i', normals_, center - points) > 1e-09) & (z > 0) & (uv[:, 0] >= 6) & (uv[:, 0] < width - 6) & (uv[:, 1] >= 6) & (uv[:, 1] < height - 6)
    return count

def sample(vertices, triangles, n=6000):
    t = vertices[triangles]
    area = np.linalg.norm(np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0]), axis=1) / 2
    if not len(t) or not area.sum():
        return (np.empty((0, 3)), np.empty(0, int))
    rng = np.random.default_rng(78425)
    ids = rng.choice(len(t), n, p=area / area.sum())
    uv = rng.random((n, 2))
    uv[uv.sum(axis=1) > 1] = 1 - uv[uv.sum(axis=1) > 1]
    return (t[ids, 0] + uv[:, 0, None] * (t[ids, 1] - t[ids, 0]) + uv[:, 1, None] * (t[ids, 2] - t[ids, 0]), ids)

def distances(points, vertices, triangles):
    t = vertices[triangles]
    out = []
    if not len(t):
        return np.full(len(points), np.inf)
    for p in points:
        q = closest_point(t, np.broadcast_to(p, (len(t), 3)))
        out.append(np.linalg.norm(q - p, axis=1).min())
    return np.asarray(out)

def stats(d):
    finite = d[np.isfinite(d)]
    return {
        'count': len(d),
        'finiteCount': len(finite),
        'p50': float(np.median(finite)) if len(finite) else None,
        'p95': float(np.quantile(finite, 0.95)) if len(finite) else None,
        'max': float(finite.max()) if len(finite) else None,
    }

def validate_fixture(manifest):
    """This evaluator intentionally supports ONLY the fixed convex cuboid generator."""
    z = float(np.float32(0.65))
    expected_v = np.array([[x, y, zz] for x in [-1.0, 1.0] for y in [-0.75, 0.75] for zz in [-z, z]])
    expected_t = np.array([[0, 1, 3], [0, 3, 2], [2, 3, 7], [2, 7, 6], [6, 7, 5], [6, 5, 4], [4, 5, 1], [4, 1, 0], [2, 6, 4], [2, 4, 0], [7, 3, 1], [7, 1, 5]])
    if manifest.get('schema') != 1 or manifest.get('synthetic') is not True or (manifest.get('width'), manifest.get('height')) != (160, 120):
        raise ValueError('Unsupported fixed fixture schema')
    if not np.array_equal(np.asarray(manifest['vertices']), expected_v) or not np.array_equal(np.asarray(manifest['triangles']), expected_t):
        raise ValueError('Only exact generated convex cuboid reference geometry is supported')
    cameras = np.asarray(manifest['cameras'], float)
    if cameras.shape != (8, 16) or not np.all(np.isfinite(cameras)) or (not np.all(cameras[:, 12:14] > 0)):
        raise ValueError('Invalid finite positive-intrinsic camera rows')
    for c in cameras:
        r = c[:9].reshape(3, 3)
        if not np.allclose(r @ r.T, np.eye(3), atol=1e-06) or not np.isclose(np.linalg.det(r), 1, atol=1e-06):
            raise ValueError('Invalid camera rotation')

def evaluate(manifest, vertices, triangles):
    validate_fixture(manifest)
    vertices = np.asarray(vertices, float)
    triangles = np.asarray(triangles)
    if vertices.ndim != 2 or vertices.shape[1] != 3 or (not np.all(np.isfinite(vertices))):
        raise ValueError('Candidate vertices must be finite Nx3')
    if triangles.ndim != 2 or triangles.shape[1] != 3 or (not np.issubdtype(triangles.dtype, np.integer)):
        raise ValueError('Candidate triangles must be integer Nx3')
    if len(triangles) and (triangles.min() < 0 or triangles.max() >= len(vertices)):
        raise ValueError('Candidate triangle index out of range')
    v = np.asarray(manifest['vertices'], float)
    t = np.asarray(manifest['triangles'], int)
    n = normals(v, t)
    cs = np.asarray(manifest['cameras'])
    w = manifest['width']
    h = manifest['height']
    diagonal = float(np.linalg.norm(np.ptp(v, axis=0)))
    for c in cs:
        uv, z = project(v, c)
        if not np.all((z > 0) & (uv[:, 0] >= 6) & (uv[:, 0] < w - 6) & (uv[:, 1] >= 6) & (uv[:, 1] < h - 6)):
            raise ValueError('Reference full-face visibility proof failed: partial-FOV fixture unsupported')
    rows = []
    for i, c in enumerate(cs):
        truth, faces = raster(v, t, c, w, h)
        pred, _ = raster(vertices, triangles, c, w, h)
        yy, xx = np.where(faces >= 0)
        z = truth[yy, xx]
        xyz = np.stack(((xx - c[14]) * z / c[12], (yy - c[15]) * z / c[13], z), axis=1)
        world = (xyz - c[9:12]) @ c[:9].reshape(3, 3)
        observable = observable_convex(world, n[faces[yy, xx]], cs, w, h) >= 2
        yy = yy[observable]
        xx = xx[observable]
        covered = np.isfinite(pred[yy, xx])
        error = np.abs(pred[yy[covered], xx[covered]] - truth[yy[covered], xx[covered]])
        rows.append({
            'view': i,
            'observablePixels': len(xx),
            'coveredPixels': int(covered.sum()),
            'coverage': float(covered.mean()) if len(xx) else None,
            'absoluteCameraZError': stats(error),
            'accurateCoverageCDF': {str(f): float(np.count_nonzero(error <= diagonal * f) / len(xx)) if len(xx) else None for f in [0.005, 0.01, 0.02, 0.05]},
            'candidateTrianglesSkippedNonpositiveCameraZ': int(np.count_nonzero(np.any(project(vertices, c)[1][triangles] <= 0, axis=1))),
        })
    if not sum((r['observablePixels'] for r in rows)):
        raise ValueError('No observable reference pixels')
    points, ids = sample(v, t)
    mask = observable_convex(points, n[ids], cs, w, h) >= 2
    visible = points[mask]
    d = distances(visible, vertices, triangles)
    candidate, ci = sample(vertices, triangles)
    observableFaces = np.where(observable_convex(v[t].mean(axis=1), n, cs, w, h) >= 2)[0]
    accuracy = distances(candidate, v, t[observableFaces])
    return {
        'referenceBoundingBoxDiagonal': diagonal,
        'perView': rows,
        'projectedPixelCoverage': sum((r['coveredPixels'] for r in rows)) / sum((r['observablePixels'] for r in rows)),
        'referenceSurfaceSampling': {
            'totalAreaUniformSamples': len(points),
            'observableInAtLeastTwoViews': len(visible),
            'unknownExcludedSamples': int((~mask).sum()),
            'referenceToCandidateDistance': stats(d),
            'distanceCDF': {str(f): float(np.mean(d <= diagonal * f)) for f in [0.005, 0.01, 0.02, 0.05]},
            'cdfUnits': 'fraction of reference bbox diagonal; descriptive curve, NOT acceptance thresholds',
        },
        'candidateToObservableReferenceDistance': stats(accuracy),
        'candidateDistanceCDF': {str(f): float(np.mean(accuracy <= diagonal * f)) if len(accuracy) else None for f in [0.005, 0.01, 0.02, 0.05]},
        'candidateSampling': '6000 deterministic area-uniform candidate samples including ALL candidate triangles; unknown/outside-visible-truth geometry is not silently cropped',
        'referenceFullFaceVisibilityProof': 'all reference vertices are positive-z and inside 6px margin in ALL cameras; convex planar-face visibility is constant per triangle',
        'alignment': 'identity: known-camera synthetic world frame; no ICP or fitted scale',
        'observability': 'convex-reference front-face visibility, positive camera z, 6px image margin, >=2 cameras; not valid for nonconvex replacement fixtures',
    }
