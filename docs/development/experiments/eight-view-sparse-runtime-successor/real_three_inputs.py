"""Closed licensed three-view partial-arc originals; CI preparation only, production runtime stays offline.

No preflight points, poses, masks, calibration profile, or raw EXIF evidence is admitted.
The original files contain unrelated metadata and must never become public evidence.
"""
import hashlib
import json
from pathlib import Path

DATASET = 'alansartlog-skull-cameramoves-flash-no-background-originals-10-11-12'
COMMIT = '0f31c229ac289a615b3ae981a5f584856bb24c47'
SOURCE = 'https://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background'
BASE_URL = 'https://gitlab.com/api/v4/projects/8724498/repository/files/'
INTRINSICS_PROVENANCE = 'ESTIMATED_35MM_EQUIVALENT_88_NO_CALIBRATION_PROFILE'
HASHES = {
    '10.JPG': '9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e',
    '11.JPG': 'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c',
    '12.JPG': 'f771973ad8f3092d6e869d29c4cb88a0ad67d30c6cf03dfe9dce70c98cdd9bb1',
}
SIZES = {'10.JPG': 3086546, '11.JPG': 3124295, '12.JPG': 3138412, 'README.md': 1205}
README_SHA256 = '01e8bfdc1aafda11e61fb13fb6b05b3701349f3820a9f04fc0c744700e2363e8'
SOURCE_HASHES = {**HASHES, 'README.md': README_SHA256}
URLS = {name: BASE_URL + name + '/raw?ref=' + COMMIT for name in SOURCE_HASHES}
MANIFEST = {'schema': 1, 'dataset': DATASET, 'synthetic': False, 'width': 4272, 'height': 2848,
            'intrinsicsProvenance': INTRINSICS_PROVENANCE, 'nominalFocal35': 88.0,
            'images': [{'id': name[:-4], 'path': 'images/' + name, 'sha256': digest} for name, digest in HASHES.items()]}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode('ascii')

MANIFEST_BYTES = encoded(MANIFEST)
POLICY_BYTES = encoded({'schema': 1, 'dataset': DATASET, 'manifestSha256': digest(MANIFEST_BYTES),
                        'images': MANIFEST['images'], 'intrinsicsProvenance': INTRINSICS_PROVENANCE,
                        'nominalFocal35': 88.0})

# Import the immutable stereo adapter solely for parameter-independent utilities and
# pinned privacy-reader installation. Its dataset/manifest globals are never mutated.
import importlib.util as _importlib_util
_spec = _importlib_util.spec_from_file_location('licensed_stereo_utilities', Path(__file__).with_name('real_stereo_inputs.py'))
_stereo = _importlib_util.module_from_spec(_spec)
_spec.loader.exec_module(_stereo)
require = _stereo.require
canonical_directory = _stereo.canonical_directory
bounded_file = _stereo.bounded_file
jpeg_dimensions = _stereo.jpeg_dimensions

ATTRIBUTION_BYTES = (
    '"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background # 10" and\n'
    '"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background # 11" and\n'
    '"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background # 12"\n'
    'by alansartlog (https://alansartlog.com), CC BY 4.0\n'
    'https://creativecommons.org/licenses/by/4.0/\n' + SOURCE + '\n'
    'Pinned source commit: ' + COMMIT + '\n'
    'Original input JPEG bytes are unchanged and are not included in this evidence.\n'
    'Retained reconstructed geometry, photographic texture atlases and exports are derivative\n'
    'outputs produced by the scanner from these images, not original author-provided models.\n'
    'Analysis decoding/resampling and reconstruction change the representation; no author\n'
    'endorsement is implied. Three-view partial arc with flash lighting and estimated 88mm\n'
    '35mm-equivalent input, not measured calibration, full-object or metric/device quality.\n'
).encode('utf-8')
PROVENANCE_BYTES = encoded({
    'schema': 1, 'dataset': DATASET, 'sourceCommit': COMMIT,
    'license': 'CC-BY-4.0', 'licenseUrl': 'https://creativecommons.org/licenses/by/4.0/',
    'author': 'alansartlog', 'authorUrl': 'https://alansartlog.com',
    'licenseReadme': {'url': URLS['README.md'], 'bytes': SIZES['README.md'], 'sha256': README_SHA256},
    'images': [{'name': name, 'url': URLS[name], 'bytes': SIZES[name], 'sha256': value,
                'width': 4272, 'height': 2848} for name, value in HASHES.items()],
    'intrinsicsProvenance': INTRINSICS_PROVENANCE, 'nominalFocal35': 88.0,
    'selection': 'Fixed 10/11/12 partial arc selected after separate native assessment; not a sweep or held-out benchmark.',
    'privacy': 'No original JPEGs, raw EXIF, camera serials, owner/contact data or import databases retained.',
    'scope': 'REAL_LICENSED_THREE_PARTIAL_ARC_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY',
})

LICENSE_README_BYTES = _stereo.LICENSE_README_BYTES
LICENSE_EVIDENCE = {
    'real-input-license-readme.md': LICENSE_README_BYTES,
    'real-input-provenance.json': PROVENANCE_BYTES,
    'real-input-attribution.txt': ATTRIBUTION_BYTES,
}

def validate_source(source):
    source = canonical_directory(source)
    require({p.name for p in source.iterdir()} == set(SOURCE_HASHES), 'exact downloaded source inventory required')
    buffers = {}
    for name, expected in SOURCE_HASHES.items():
        data = bounded_file(source / name, SIZES[name])
        require(digest(data) == expected, 'fixed source checksum')
        if name in HASHES:
            require(jpeg_dimensions(data) == (4272, 2848), 'fixed source JPEG dimensions')
        buffers[name] = data
    return buffers

fresh_directory = _stereo.fresh_directory

def fetch(run, destination):
    """CI-only four fixed public files. No redirects, retries, caller URLs or output-byte logs."""
    require(run.env.get('GITHUB_ACTIONS') == 'true' and
            run.env.get('GITHUB_REPOSITORY') == 'cheshmakzanoon-ops/3d-scanner', 'licensed input fetch is CI preparation only')
    require(Path(destination) == canonical_directory(run.work) / 'licensed-source', 'fixed owned download destination required')
    destination = fresh_directory(destination)
    # README first: refuse mismatched license provenance before downloading photographs.
    for name in ('README.md', '10.JPG', '11.JPG', '12.JPG'):
        run.remaining(60)
        target = destination / name
        label = 'licensed-input-' + ('license' if name == 'README.md' else name[:2])
        raw, code = run.command(label, ['curl', '-q', '--fail', '--silent', '--show-error',
            '--proto', '=https', '--max-time', '60', '--max-filesize', str(SIZES[name]),
            '--output', str(target), '--write-out', '%{http_code}', '--', URLS[name]],
            seconds=60, limit=4096)
        require(code == 0 and raw == b'200', 'fixed input HTTP response must be 200 without redirects')
        require(digest(bounded_file(target, SIZES[name])) == SOURCE_HASHES[name], 'downloaded source checksum')
    validate_source(destination)
    return destination

def validate(root):
    root = canonical_directory(root)
    require({p.name for p in root.iterdir()} == {'observation-inputs.json', 'images'}, 'unexpected staged entry; truth forbidden')
    images = canonical_directory(root / 'images')
    require({p.name for p in images.iterdir()} == set(HASHES), 'exact three image inventory required')
    require(bounded_file(root / 'observation-inputs.json', len(MANIFEST_BYTES)) == MANIFEST_BYTES, 'fixed observation manifest changed')
    for name, expected in HASHES.items():
        data = bounded_file(images / name, SIZES[name])
        require(digest(data) == expected, 'fixed JPEG checksum')
        require(jpeg_dimensions(data) == (4272, 2848), 'fixed JPEG dimensions')
    return {'schema': 1, 'manifestSha256': digest(MANIFEST_BYTES),
            'images': {'images/' + name: value for name, value in HASHES.items()}, 'policySha256': digest(POLICY_BYTES)}

write_fresh = _stereo.write_fresh

def stage(source, destination):
    """Exact downloaded original bytes only, no source sibling or calibration data admission."""
    buffers = validate_source(source)
    destination = fresh_directory(destination); (destination / 'images').mkdir(mode=0o700)
    for name in HASHES: write_fresh(destination / 'images' / name, buffers[name])
    write_fresh(destination / 'observation-inputs.json', MANIFEST_BYTES)
    return validate(destination)

def public_evidence(source):
    """Only author license text, minimal public provenance and derivative attribution, never JPEG/EXIF."""
    buffers = validate_source(source)
    require(buffers['README.md'] == LICENSE_README_BYTES, 'license bytes differ from retained evidence')
    return dict(LICENSE_EVIDENCE)

# Reader state stays owned by its original module, while image iteration and source
# admission below are owned by this closed three-photo profile.
prepare_privacy_reader = _stereo.prepare_privacy_reader
_marker_variants = _stereo._marker_variants

def private_markers(source):
    """Read private original EXIF into memory only; original bytes remain unchanged."""
    import io
    _stereo._verify_privacy_reader()
    buffers = validate_source(source)
    values = []
    try:
        for name in HASHES:
            with _stereo._PRIVACY_READER.open(io.BytesIO(buffers[name])) as image:
                exif = image.getexif()
                require(not exif.get_ifd(34853), 'unexpected private GPS metadata')
                for tag in (270, 315, 33432, 40091, 40092, 40093, 40094, 40095):
                    if tag in exif:
                        values.append((exif[tag], 'utf-16-le' if tag >= 40091 else 'utf-8'))
                photo = exif.get_ifd(34665)
                for tag in (42032, 42033, 42037):
                    if tag in photo: values.append((photo[tag], 'utf-8'))
                if 37510 in photo: values.append((photo[37510], 'comment'))
                if 37500 in photo: values.append((photo[37500], 'opaque'))
        result = _marker_variants(values)
        require(result, 'private metadata markers missing')
        _stereo._verify_privacy_reader()
        return result
    except BaseException:
        raise ValueError('private original metadata inspection failed') from None
