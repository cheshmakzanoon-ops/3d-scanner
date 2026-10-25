"""Closed licensed eight-view wider-orbit originals; CI preparation only, production runtime stays offline.

No preflight points, poses, masks, calibration profile, or raw EXIF evidence is admitted.
The original files contain unrelated metadata and must never become public evidence.
"""
import hashlib
import json
from pathlib import Path

DATASET = 'alansartlog-skull-cameramoves-flash-no-background-originals-01-03-05-07-09-11-13-15'
COMMIT = '0f31c229ac289a615b3ae981a5f584856bb24c47'
SOURCE = 'https://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background'
BASE_URL = 'https://gitlab.com/api/v4/projects/8724498/repository/files/'
INTRINSICS_PROVENANCE = 'ESTIMATED_35MM_EQUIVALENT_88_NO_CALIBRATION_PROFILE'
HASHES = {'01.JPG': '49cc69b7e2e8999252c71536cc5a8a0ee32ae25d56d896969542653fa93e4858',
 '03.JPG': '3aae3bbf830469ea8711915545627692b9655de9f881fa0b9a128543b8fb4be1',
 '05.JPG': '762c2fe0f5780bc57ae7c3e65bafc5046c60756b65428576aa47d54df03e994c',
 '07.JPG': 'cef6c51017e11d2c35f359f80920a28d866c6bc7d1d4f6e876e1718a328ec79c',
 '09.JPG': '6190f02734af4cd137426afba19352d2c3b412e4016ec844eae61c882e341eb4',
 '11.JPG': 'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c',
 '13.JPG': '06074dbb72eaf56786bfcb9d69da66aa47657f076090d9e470ba2e5c19169700',
 '15.JPG': '3366c2efb3f7b70114b94792b9c6b85141b4631111f8bb0f447d370ca0fb098f'}
GIT_BLOBS = {'01.JPG': 'c47e25bb6d5f730e063a0fa5db1ed2a7f246e277',
 '03.JPG': 'b82ff49055ca9ba0ba59b729cfcc981e21f305c0',
 '05.JPG': 'fdc1a0c91d0442d9eebf8087db8a13853264ce73',
 '07.JPG': '782dad342298198bfb4917f327f17dc5877de6ab',
 '09.JPG': '105d119cd8463160cbfb1dc108578e48a6131173',
 '11.JPG': 'ac4ffa52b12e6946e6a9d447d7fd499d58736e40',
 '13.JPG': '55456bcf71eb13f67f82952741e72f1ac601f69d',
 '15.JPG': 'a639b7d0c9631d03f3a8f115139f9cc2fe96c242'}
SIZES = {'01.JPG': 3079602,
 '03.JPG': 3425910,
 '05.JPG': 3399588,
 '07.JPG': 3094545,
 '09.JPG': 3202240,
 '11.JPG': 3124295,
 '13.JPG': 3469493,
 '15.JPG': 3268609,
 'README.md': 1205}
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

ATTRIBUTION_BYTES = '"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background" # 01, # 03, # 05, # 07, # 09, # 11, # 13, # 15\nby alansartlog (https://alansartlog.com), CC BY 4.0\nhttps://creativecommons.org/licenses/by/4.0/\nhttps://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background\nPinned source commit: 0f31c229ac289a615b3ae981a5f584856bb24c47\nOriginal input JPEG bytes are unchanged and are not included in this evidence.\nRetained sparse geometry, feature and camera estimates are derivative scanner analysis, not author-provided models.\nAnalysis decoding/resampling and reconstruction change the representation; no author endorsement is implied.\nEight-view wider-orbit sparse-prefix diagnostic, not complete-object, surface, metric or device quality.\nFlash lighting and estimated 88mm 35mm-equivalent input are not measured calibration.\n'.encode("utf-8")
PROVENANCE_BYTES = encoded({
    'schema': 1, 'dataset': DATASET, 'sourceCommit': COMMIT,
    'license': 'CC-BY-4.0', 'licenseUrl': 'https://creativecommons.org/licenses/by/4.0/',
    'author': 'alansartlog', 'authorUrl': 'https://alansartlog.com',
    'licenseReadme': {'url': URLS['README.md'], 'bytes': SIZES['README.md'], 'sha256': README_SHA256},
    'images': [{'name': name, 'url': URLS[name], 'bytes': SIZES[name], 'sha256': value,
                'width': 4272, 'height': 2848, 'gitBlobSha1': GIT_BLOBS[name]} for name, value in HASHES.items()],
    'intrinsicsProvenance': INTRINSICS_PROVENANCE, 'nominalFocal35': 88.0,
    'selection': 'Predeclared 2026-10-25T21:27:46.221733+00:00: every other numbered image of apparent first similar-height orbit 01-16, inferred from official Preview before any eight-view feature/geometry evaluation. No image substitution, success-based selection or sweep. Existing 10/11/12 comparison unchanged; adjacent overlap and whole-object coverage are not guaranteed.',
    'selectionDeclarationSha256': 'c7295eac14abc1d829d972a64a4a947542294673fb7e6415cc197ec610f9bf1f',
    'privacy': 'No original JPEGs, raw EXIF, camera serials, owner/contact data or import databases retained.',
    'scope': 'REAL_LICENSED_EIGHT_WIDER_ORBIT_HOST_SPARSE_PREFIX_NOT_DEVICE_OR_SURFACE_QUALITY',
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
            require(hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest() == GIT_BLOBS[name], 'fixed source Git blob identity')
            require(jpeg_dimensions(data) == (4272, 2848), 'fixed source JPEG dimensions')
        buffers[name] = data
    return buffers

fresh_directory = _stereo.fresh_directory

def fetch(run, destination):
    """CI-only nine fixed public files. No redirects, retries, caller URLs or output-byte logs."""
    require(run.env.get('GITHUB_ACTIONS') == 'true' and
            run.env.get('GITHUB_REPOSITORY') == 'cheshmakzanoon-ops/3d-scanner', 'licensed input fetch is CI preparation only')
    require(Path(destination) == canonical_directory(run.work) / 'licensed-source', 'fixed owned download destination required')
    destination = fresh_directory(destination)
    # README first: refuse mismatched license provenance before downloading photographs.
    for name in ('README.md', *HASHES):
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
    require({p.name for p in images.iterdir()} == set(HASHES), 'exact eight image inventory required')
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
# admission below are owned by this closed eight-photo profile.
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
