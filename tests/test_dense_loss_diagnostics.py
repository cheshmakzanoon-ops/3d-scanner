"""Bounded originating native diagnostics; synthetic observations, never object coverage."""
import json
import sys
from pathlib import Path
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from native_api import NativeEngine


def photographs(count=3, masked=False, uniform=False):
    """Observed pinhole images of one textured plane, not a geometry oracle input."""
    width, height = 104, 76
    texture = np.random.default_rng(504).integers(0, 256, (256, 256, 3))
    y, x = np.mgrid[:height, :width]
    images, cameras = [], []
    for camera in range(count):
        u = np.clip(((x-51.5)/116*5+camera*.55)*31+128, 0, 254.999)
        v = np.clip(((y-37.5)/116*5)*31+128, 0, 254.999)
        ix, iy = u.astype(int), v.astype(int)
        a, b = (u-ix)[..., None], (v-iy)[..., None]
        rgb = np.rint((1-a)*(1-b)*texture[iy,ix]+a*(1-b)*texture[iy,ix+1]+
                      (1-a)*b*texture[iy+1,ix]+a*b*texture[iy+1,ix+1]).astype(np.uint8)
        if uniform:
            rgb[:] = 128
        rgba = np.concatenate((rgb, np.full((height,width,1),255,np.uint8)), axis=2)
        if masked and camera == count-1:
            rgba[16:60,21:70,3] = 0
        images.append(rgba)
        cameras.append([1,0,0,0,1,0,0,0,1,-camera*.55,0,0,116,116,51.5,37.5])
    return np.asarray(images), np.asarray(cameras, dtype=np.float64)


def bounded_counts(value):
    if isinstance(value, dict):
        for name, item in value.items():
            assert name not in {'x','y','rgba','rgb','image','photoId','path','spatialBins'}
            bounded_counts(item)
    elif isinstance(value, list):
        assert len(value) <= 16
        for item in value:
            bounded_counts(item)
    elif type(value) is int:
        assert 0 <= value <= 4096*100000
    else:
        assert value is None or type(value) in (str, bool)


@pytest.mark.parametrize('masked,uniform', [(False,False),(True,False),(False,True),(True,True)])
def test_originating_dense_and_surface_loss_counts_are_bounded_and_reconcile(masked, uniform):
    images, cameras = photographs(masked=masked, uniform=uniform)
    result = NativeEngine().surface(images,cameras,3.1,7.0,56,3)
    dense = result['dense']
    diagnostic = dense['lossDiagnostics']
    assert diagnostic['schema'] == 2 and diagnostic['available']
    assert diagnostic['viewCount'] == len(diagnostic['views']) == 3
    assert len(json.dumps(diagnostic)) < 32768
    bounded_counts(diagnostic)
    # Legacy counter semantics remain unchanged, even for invalid reference masks.
    views = diagnostic['views']
    actual = sum(view['photometric']['rejectionCounts']['invalidReferencePatch']+
                 view['photometric']['rejectionCounts']['validLowVariance'] for view in views)
    assert actual == dense['photometricDiagnostics']['rejectionCounts']['lowVariance']
    for view in views:
        photo = view['photometric']
        reasons = photo['rejectionCounts']['invalidOrBoundaryBestReasons']
        assert sum(reasons.values()) == photo['rejectionCounts']['invalidOrBoundaryBest']
        assert photo['rejectionCounts']['invalidOrBoundaryBest'] == reasons['ambiguousMinimum'] + reasons['nearBoundaryBest'] + reasons['farBoundaryBest']
        assert len(photo['validReferenceMeanHistogram']) == len(photo['validLowVarianceMeanHistogram']) == 16
        assert sum(photo['validReferenceMeanHistogram']) == photo['evaluatedSamples']-photo['rejectionCounts']['invalidReferencePatch']
        assert sum(photo['validLowVarianceMeanHistogram']) == photo['rejectionCounts']['validLowVariance']
        assert all(low <= valid for low,valid in zip(photo['validLowVarianceMeanHistogram'],photo['validReferenceMeanHistogram']))
    if masked:
        assert views[-1]['photometric']['rejectionCounts']['invalidReferencePatch'] > 0
    if uniform:
        assert dense['pointCount'] == 0 and 'mesh' not in result
        assert sum(view['photometric']['rejectionCounts']['validLowVariance'] for view in views) > 0
    if 'mesh' not in result:
        return
    mesh = result['mesh']
    loss = mesh['lossDiagnostics']
    assert loss['schema'] == 2 and loss['available']
    assert loss['viewCount'] == len(loss['views']) == 3
    admission = loss['topologyAdmission']
    assert set(admission) == {'passes', 'proposedWeldGroups', 'rolledBackWeldGroups',
                              'restoredSourceVertices', 'unresolvedSourceVertexLinks'}
    assert 1 <= admission['passes'] <= 3
    assert 0 <= admission['rolledBackWeldGroups'] <= admission['proposedWeldGroups']
    assert 2*admission['rolledBackWeldGroups'] <= admission['restoredSourceVertices'] <= mesh['acceptedSamples']
    if admission['rolledBackWeldGroups']:
        assert admission['passes'] >= 2
    else:
        assert admission['restoredSourceVertices'] == 0
    assert len(json.dumps(loss)) < 32768
    bounded_counts(loss)
    views = loss['views']
    assert sum(v['inputSamples'] for v in views) == mesh['inputSamples']
    assert sum(v['acceptedSamples'] for v in views) == mesh['acceptedSamples']
    assert sum(sum(v['cellCornerCounts'][:3]) for v in views) == mesh['rejectedCells']
    assert sum(sum(v['triangleRejections'].values()) for v in views) == mesh['rejectedTriangles']
    assert sum(v['acceptedTriangleAttempts'] for v in views)-loss['removedExactFaces'] == mesh['triangleCount']
    assert sum(v['attachedObservations'] for v in views) == mesh['attachedObservationCount']
    assert sum(v['unattachedObservations'] for v in views) == mesh['unattachedObservationCount']
    for v in views:
        assert sum(v['normalValidity'].values()) == v['inputSamples']
        assert sum(v['fusionCandidateFirstGate'].values()) == v['fusionCandidates']
        missing = v['missingNormalReasons']
        assert missing['sourceNormal'] + missing['accumulatorNormal'] + missing['bothNormals'] == v['fusionCandidateFirstGate']['missingNormal']


def test_originating_counters_are_isolated_between_concurrent_native_calls():
    from concurrent.futures import ThreadPoolExecutor
    images,cameras = photographs(uniform=True)
    def run(level):
        local = images.copy()
        local[...,:3] = level
        return level,NativeEngine().dense(local,cameras,3.1,7.0,56,3)
    with ThreadPoolExecutor(max_workers=2) as executor:
        outputs = list(executor.map(run,[0,255]*4))
    for level,result in outputs:
        assert result['pointCount'] == 0
        for view in result['lossDiagnostics']['views']:
            photo = view['photometric']
            bins = photo['validReferenceMeanHistogram']
            assert sum(bins) == photo['evaluatedSamples']
            assert bins[level//16] == photo['evaluatedSamples']
            assert bins == photo['validLowVarianceMeanHistogram']
