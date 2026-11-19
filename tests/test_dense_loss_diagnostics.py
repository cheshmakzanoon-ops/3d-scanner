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


def assert_topology_attribution(mesh):
    """Check independent conservation identities on actual producer counters."""
    loss = mesh['lossDiagnostics']
    assert loss['schema'] == 4 and loss['available']
    admission = loss['topologyAdmission']
    assert set(admission) == {
        'passes', 'proposedWeldGroups', 'rolledBackWeldGroups',
        'restoredSourceVertices', 'unresolvedSourceVertexLinks',
        'locallyRolledBackWeldGroups', 'fallbackRolledBackWeldGroups',
        'retainedWeldGroups', 'passDetails',
    }
    details = admission['passDetails']
    assert len(details) == admission['passes']
    assert 1 <= len(details) <= 3
    remaining = admission['proposedWeldGroups']
    rolled_back = restored = local = fallback = 0
    counters = {
        'retainedWeldGroups', 'invalidWeldGroups', 'invalidLinkReasons',
        'rolledBackWeldGroups', 'restoredSourceVertices',
    }
    for index, step in enumerate(details):
        assert set(step) == {'pass', 'inspected'} | counters
        assert type(step['pass']) is int and step['pass'] == index + 1
        assert type(step['inspected']) is bool
        if not step['inspected']:
            # No remaining welds means no inspector ran. Missing measurements
            # must stay null instead of becoming apparently measured zeros.
            assert remaining == 0 and index == len(details) - 1
            assert all(step[name] is None for name in counters)
            continue
        assert step['retainedWeldGroups'] == remaining > 0
        invalid = step['invalidWeldGroups']
        assert 0 <= invalid <= remaining
        reasons = step['invalidLinkReasons']
        assert set(reasons) == {
            'branchingLink', 'directionConflict', 'disconnectedLink',
            'invalidEndpointCount',
        }
        assert all(type(count) is int and 0 <= count <= invalid
                   for count in reasons.values())
        # A welded group can fail multiple link conditions. Individual causes
        # are bounded by invalid groups, but their sum is not a unique count.
        assert sum(reasons.values()) >= invalid
        removed = step['rolledBackWeldGroups']
        assert 0 <= removed <= remaining
        assert 2 * removed <= step['restoredSourceVertices'] <= loss['viewCount'] * removed
        if index == 0:
            assert removed == invalid
            local += removed
        else:
            # The bounded fallback removes every remaining group when an
            # invalid link persists, including groups not independently invalid.
            assert removed == (remaining if invalid else 0)
            fallback += removed
        remaining -= removed
        rolled_back += removed
        restored += step['restoredSourceVertices']
    assert rolled_back == admission['rolledBackWeldGroups']
    assert restored == admission['restoredSourceVertices']
    assert local == admission['locallyRolledBackWeldGroups']
    assert fallback == admission['fallbackRolledBackWeldGroups']
    assert local + fallback == rolled_back
    assert remaining == admission['retainedWeldGroups']
    assert remaining == admission['proposedWeldGroups'] - rolled_back
    assert remaining <= mesh['fusedObservations'] <= (loss['viewCount'] - 1) * remaining
    assert restored + mesh['fusedObservations'] + remaining <= mesh['acceptedSamples']
    if rolled_back:
        assert len(details) >= 2


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
    assert loss['schema'] == 4 and loss['available']
    assert loss['viewCount'] == len(loss['views']) == 3
    assert_topology_attribution(mesh)
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


def test_native_fixture_distinguishes_local_rejection_from_conservative_fallback():
    images, cameras = photographs()
    mesh = NativeEngine().surface(images, cameras, 3.1, 7.0, 56, 3)['mesh']
    assert_topology_attribution(mesh)
    admission = mesh['lossDiagnostics']['topologyAdmission']
    assert admission['locallyRolledBackWeldGroups'] > 0
    assert admission['fallbackRolledBackWeldGroups'] > 0
    assert admission['retainedWeldGroups'] == mesh['fusedObservations'] == 0
    assert admission['passes'] == 3
    assert admission['passDetails'][-1]['inspected'] is False
    assert any(sum(step['invalidLinkReasons'].values()) > step['invalidWeldGroups']
               for step in admission['passDetails'] if step['inspected'])


def test_topology_attribution_is_isolated_between_concurrent_native_calls():
    from concurrent.futures import ThreadPoolExecutor

    def run(masked):
        images, cameras = photographs(masked=masked)
        mesh = NativeEngine().surface(images, cameras, 3.1, 7.0, 56, 3)['mesh']
        assert_topology_attribution(mesh)
        return mesh['lossDiagnostics']['topologyAdmission']

    expected = {masked: run(masked) for masked in (False, True)}
    with ThreadPoolExecutor(max_workers=2) as executor:
        outputs = list(executor.map(run, [False, True, False, True]))
    assert outputs == [expected[masked] for masked in (False, True, False, True)]


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
