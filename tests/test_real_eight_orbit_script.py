"""Script-entry admission/sealing regressions, not actual reconstruction evidence."""
import hashlib
import json
from pathlib import Path
import runpy
from types import SimpleNamespace

import pytest
from tools.eight_view_host import experiments as e, sparse_evidence as s
from tools.eight_view_host.runtime import Failure

ROOT = Path(__file__).resolve().parents[1]

@pytest.fixture
def script():
    # The workflow executes this file, not python -m. Do not import the controller
    # as a package or replace any privacy/measurement/sealing implementation.
    module = runpy.run_path(str(ROOT / 'tools/eight_view_host/controller.py'),
                           run_name='controller_script_regression')
    assert module['__package__'] == ''
    for helper in ('measurement_reference', 'real_evidence_privacy_check',
                   'validate_real_sparse_package', 'evidence_package'):
        assert module[helper].__globals__['__package__'] == ''
    return module


def write_records(work, *, full=False):
    measurement = work / 'measurement'
    measurement.mkdir(parents=True)
    ids = ['00000000-0000-4000-8000-%012d' % i for i in range(8)]
    registered = ids if full else ids[:2]
    identity = [{'photoId': photo, 'fixturePath': name, 'imageSha256': digest}
                for photo, (name, digest) in zip(ids, s.INPUT_HASHES.items())]
    geometry = {'finalSparseOutcome': 'verified_anchor_fallback', 'pointCount': 1}
    if full:
        geometry.update(finalSparseOutcome='global_typed_ba_admitted12', bundleAdjustment={
            'inputObservations': 2, 'usableObservations': 2, 'rejectedObservations': 0,
            'acceptedSteps': 1, 'attemptedIterations': 1, 'robustCostBefore': .01,
            'robustCostAfter': .001, 'medianBeforeNormalized': .01, 'medianAfterNormalized': .001})
    result = {'schema': 1, 'synthetic': False,
              'classification': s.CLASSIFICATION if full else 'INCOMPLETE_OR_FALLBACK',
              'inputPhotoIds': ids, 'registeredPhotoIds': registered,
              'unregisteredPhotoIds': [] if full else ids[2:], 'geometry': geometry}
    diagnostics = {'schema': 1,
        'graphScope': 'REGISTERED_CAMERA_CHECKPOINT_GRAPH_NOT_ALL_INPUT_INITIALIZER_EDGES',
        'allInputVerifiedEdgeIdentities': 'NOT_EXPOSED_BY_PRODUCTION_DIAGNOSTICS',
        'registeredPhotoIds': registered, 'trackCount': 1, 'observationCount': 2,
        'cameras': [{'photoId': photo, 'width': 1600, 'height': 1067,
                     'fx': 3911., 'fy': 3911., 'cx': 799.5, 'cy': 533.,
                     'rotationWorldToCamera': [1,0,0,0,1,0,0,0,1],
                     'translationWorldToCamera': [0,0,0]} for photo in registered],
        'tracks': [{'ordinal': 0, 'x': 1., 'y': 2., 'z': 3., 'reprojectionRmsNormalized': .001,
                    'observations': [{'cameraIndex': i, 'featureIndex': 7,
                                      'xNormalized': .01, 'yNormalized': .02} for i in range(2)]}],
        'verifiedMatchGraph': [{'firstCamera': 0, 'secondCamera': 1,
                               'firstPhotoId': ids[0], 'secondPhotoId': ids[1],
                               'verifiedMatches': 30, 'inliers': 25, 'medianErrorNormalized': .001}]}
    records = {'input-identity.json': identity, 'sparse-result.json': result,
               'sparse-diagnostics.json': diagnostics,
               'pair-events.json': [{'pairNumber': i, 'pairCount': 28, 'phase': 'READY',
                                     'geometryEntered': True} for i in range(1,29)],
               'sparse-timing.json': {'schema': 1, 'elapsedMillis': 10,
                   'scope': 'HOST_MONOTONIC_NOT_DEVICE_PERFORMANCE', 'featureEvents': [], 'pairEvents': []}}
    if not full:
        records['failure.json'] = {'schema': 1, 'status': 'FAILED_NOT_QUALITY_PASS',
                                   'phase': 'BEFORE_OR_AT_SPARSE'}
    for name, value in records.items():
        (measurement / name).write_text(json.dumps(value))
    observed = {'images': dict(s.INPUT_HASHES)}
    (work / 'observation-proof.json').write_text(json.dumps(observed))
    return observed


def owned_run(work):
    return SimpleNamespace(work=work, real_private_markers=(b'private-camera-owner',),
                           remaining=lambda maximum, **kwargs: maximum, commands=[])


@pytest.mark.parametrize('helper', ['privacy', 'package', 'measurement'])
def test_script_context_all_three_sparse_helpers(script, tmp_path, helper):
    observed = write_records(tmp_path, full=True)
    run = owned_run(tmp_path)
    if helper == 'privacy':
        script['real_evidence_privacy_check']('measurement/sparse-result.json',
            (tmp_path / 'measurement/sparse-result.json').read_bytes(), run, profile=e.REAL_EIGHT_ORBIT)
        return
    if helper == 'package':
        script['validate_real_sparse_package'](run)
        return
    proof = script['measurement_reference'](tmp_path, e.REAL_EIGHT_ORBIT, run, observed=observed)
    assert proof['registeredCameraCount'] == 8 and proof['pairCount'] == 28
    assert proof['classification'] == s.CLASSIFICATION
    assert len(proof['supportingReferences']) == 5


def test_script_failure_seals_typed_diagnostics_and_hashes(script, tmp_path):
    work = tmp_path / 'work'
    write_records(work)
    run = owned_run(work)
    receipt = {'status': 'FAIL', 'experiment': e.REAL_EIGHT_ORBIT,
               'firstFailure': 'Actual selected sparse invocation failed',
               'commandTranscriptRequired': True}
    receipt['commandsReference'] = script['command_reference'](run)
    output = script['evidence_package'](run, receipt, tmp_path / 'evidence')
    retained = json.loads((output / 'receipt.json').read_text())
    assert retained['status'] == 'FAIL' and retained['evidence']['complete'] is True
    assert 'measurementReferenceFailure' not in retained and 'commandTranscriptFailure' not in retained
    for name in ('failure.json', 'sparse-result.json', 'sparse-diagnostics.json'):
        assert (output / 'measurement' / name).read_bytes() == (work / 'measurement' / name).read_bytes()
    assert json.loads((output / 'measurement/sparse-result.json').read_text())['classification'] == 'INCOMPLETE_OR_FALLBACK'
    for line in (output / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        assert hashlib.sha256((output / name).read_bytes()).hexdigest() == digest


@pytest.mark.parametrize('invalid', ['private-marker', 'unknown-field', 'opaque-cache', 'foreign-input'])
def test_script_failed_evidence_still_fails_closed(script, tmp_path, invalid):
    work = tmp_path / 'work'
    write_records(work)
    root = work / 'measurement'
    if invalid in ('private-marker', 'unknown-field'):
        path = root / 'failure.json'
        value = json.loads(path.read_text())
        value['message'] = 'private-camera-owner' if invalid == 'private-marker' else '/unreviewed/path'
        path.write_text(json.dumps(value))
    elif invalid == 'opaque-cache':
        (root / 'manifest-MATCHES.rfsm').write_bytes(b'RFSMopaque')
    else:
        path = root / 'input-identity.json'
        value = json.loads(path.read_text()); value[0]['imageSha256'] = 'f' * 64
        path.write_text(json.dumps(value))
    output = tmp_path / 'evidence'
    with pytest.raises(Failure):
        script['evidence_package'](owned_run(work), {'status': 'FAIL'}, output)
    # Admission precedes copying, so even FAIL cannot leak invalid evidence.
    assert not list(output.rglob('*'))


def test_selected_failure_does_not_promise_later_retention():
    source = (ROOT / 'tools/eight_view_host/controller.py').read_text()
    assert "raise Failure('Actual selected sparse invocation failed')" in source
    assert 'original logs/events retained' not in source
