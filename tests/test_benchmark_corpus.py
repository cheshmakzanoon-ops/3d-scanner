import copy
import json
from pathlib import Path
import pytest
from tools.qa.corpus.fixture import generate
from tools.qa.corpus.manifest import validate, InvalidManifest, sha256, discover


@pytest.fixture
def corpus(tmp_path):
    return generate(tmp_path), tmp_path


def test_reproducible(tmp_path):
    a, b = tmp_path/'a', tmp_path/'b'
    assert generate(a) == generate(b)
    assert (a/'manifest.json').read_bytes() == (b/'manifest.json').read_bytes()
    assert validate(generate(a), a)['physical_qualification'] == 'NOT_APPLICABLE_SYNTHETIC'


@pytest.mark.parametrize('mutate', [
    lambda m: m.update(schema_version=2), lambda m: m.update(units='cm'),
    lambda m: m.update(metric_scale_verified=True), lambda m: m.pop('dataset_id'),
    lambda m: m['permission'].pop('provenance'), lambda m: m['permission'].update(evaluation=False),
    lambda m: m['permission'].update(redistribution='yes'), lambda m: m.update(split='held_out'),
    lambda m: m['reference'].update(independent=False), lambda m: m['reference'].pop('provenance'),
    lambda m: m['reference'].update(method='candidate_reconstruction'), lambda m: m['reference'].update(units='m'),
    lambda m: m['photos'][1].update(sha256=m['photos'][0]['sha256']),
    lambda m: m['photos'][1].update(id=m['photos'][0]['id']),
    lambda m: m['photos'][0].update(path='../escape'), lambda m: m['calibration'].update(lens_id='wrong'),
    lambda m: m['photos'][0].update(lens_id='wrong'), lambda m: m['photos'][0].update(width=99),
    lambda m: m['photos'][0].update(role='held_out'), lambda m: m.update(kind='real'),
])
def test_invalid_metadata(corpus, mutate):
    m, root = corpus
    mutate(m)
    with pytest.raises(InvalidManifest):
        validate(m, root)


def test_tamper(corpus):
    m, root = corpus
    (root/m['photos'][0]['path']).write_bytes(b'tampered')
    with pytest.raises(InvalidManifest, match='tampered'):
        validate(m, root)


def test_calibration_bytes_checked(corpus):
    m, root = corpus
    p = root/'calibration.json'
    data = json.loads(p.read_text()); data['K'][0][0] = -1
    p.write_text(json.dumps(data)); m['calibration']['sha256'] = sha256(p)
    with pytest.raises(InvalidManifest, match='intrinsics'):
        validate(m, root)


def test_symlink_escape(corpus, tmp_path):
    m, root = corpus
    p = root/m['photos'][0]['path']; p.unlink(); p.symlink_to('/etc/hosts')
    with pytest.raises(InvalidManifest, match='symlink'):
        validate(m, root)


def test_authorized_real(corpus):
    m, root = corpus
    # Test-only operator attestation, never a real dataset receipt.
    m.update(kind='real', split='development')
    m['capture']['physical'] = True
    m['reference'].update(method='independent_scanner', provenance='test-only attestation')
    assert validate(m, root)['physical_qualification'] == 'ELIGIBLE_INPUT_ONLY'
    assert discover(root) == [root/'manifest.json']


def test_cross_split_leakage(tmp_path):
    from tools.qa.corpus.manifest import audit_corpus
    a, b = tmp_path/'a', tmp_path/'b'
    ma, mb = generate(a), generate(b)
    mb.update(dataset_id='other-real', kind='real', split='held_out')
    mb['capture']['physical'] = True; mb['reference']['method'] = 'independent_scanner'
    (b/'manifest.json').write_text(json.dumps(mb))
    with pytest.raises(InvalidManifest, match='leakage'):
        audit_corpus([a/'manifest.json', b/'manifest.json'])


def test_decoded_dimensions(corpus):
    m, root = corpus
    for p in m['photos']:
        p['width'] = 10
    with pytest.raises(InvalidManifest, match='decoded'):
        validate(m, root)
