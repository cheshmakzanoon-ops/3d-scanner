"""Closed licensed stereo originals; CI preparation only, production runtime stays offline.

No preflight points, poses, masks, calibration profile, or raw EXIF evidence is admitted.
The original files contain unrelated metadata and must never become public evidence.
"""
import hashlib
import json
import os
import stat
from pathlib import Path

DATASET = 'alansartlog-skull-cameramoves-flash-no-background-originals-10-11'
COMMIT = '0f31c229ac289a615b3ae981a5f584856bb24c47'
SOURCE = 'https://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background'
BASE_URL = 'https://gitlab.com/api/v4/projects/8724498/repository/files/'
INTRINSICS_PROVENANCE = 'ESTIMATED_35MM_EQUIVALENT_88_NO_CALIBRATION_PROFILE'
HASHES = {
    '10.JPG': '9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e',
    '11.JPG': 'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c',
}
SIZES = {'10.JPG': 3086546, '11.JPG': 3124295, 'README.md': 1205}
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

def require(condition, message):
    if not condition:
        raise ValueError(message)

def canonical_directory(path):
    path = Path(path)
    require(path.is_absolute() and path.absolute() == path.resolve(strict=True), 'directory alias/noncanonical path')
    require(stat.S_ISDIR(path.lstat().st_mode) and not path.is_symlink(), 'invalid directory')
    return path

def bounded_file(path, exact_size):
    path = Path(path)
    require(path.is_absolute() and path.absolute() == path.resolve(strict=True), 'file alias/noncanonical path')
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_size == exact_size and exact_size > 0, 'file type/size bound')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        require((opened.st_dev, opened.st_ino, opened.st_size) == (before.st_dev, before.st_ino, before.st_size), 'file replaced before read')
        blocks = []; total = 0
        while True:
            block = os.read(fd, min(65536, exact_size + 1 - total))
            if not block: break
            total += len(block); require(total <= exact_size, 'file grew beyond limit'); blocks.append(block)
        after = os.fstat(fd); current = path.lstat()
        key = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        require(key(before) == key(after) == key(current) and total == before.st_size == exact_size and not path.is_symlink() and path.absolute() == path.resolve(strict=True), 'file changed during read')
        return b''.join(blocks)
    finally:
        os.close(fd)

def jpeg_dimensions(data):
    require(data[:2] == b'\xff\xd8', 'JPEG signature')
    index = 2
    while index < len(data):
        require(data[index] == 0xff, 'JPEG marker'); index += 1
        while index < len(data) and data[index] == 0xff: index += 1
        require(index < len(data), 'JPEG truncated marker'); marker = data[index]; index += 1
        require(marker not in (0, 0xd8, 0xd9, 0xda), 'JPEG missing baseline frame')
        require(index + 2 <= len(data), 'JPEG truncated length')
        size = int.from_bytes(data[index:index+2], 'big')
        require(size >= 2 and index + size <= len(data), 'JPEG segment bound')
        if marker == 0xc0:
            require(size == 17 and data[index+2] == 8 and data[index+7] == 3, 'JPEG baseline RGB components')
            return int.from_bytes(data[index+5:index+7], 'big'), int.from_bytes(data[index+3:index+5], 'big')
        require(marker not in (0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf), 'Unsupported JPEG frame')
        index += size
    raise ValueError('JPEG frame absent')

ATTRIBUTION_BYTES = (
    '"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background # 10" and\n'
    '"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background # 11"\n'
    'by alansartlog (https://alansartlog.com), CC BY 4.0\n'
    'https://creativecommons.org/licenses/by/4.0/\n' + SOURCE + '\n'
    'Pinned source commit: ' + COMMIT + '\n'
    'Original input JPEG bytes are unchanged and are not included in this evidence.\n'
    'Retained reconstructed geometry, photographic texture atlases and exports are derivative\n'
    'outputs produced by the scanner from these images, not original author-provided models.\n'
    'Analysis decoding/resampling and reconstruction change the representation; no author\n'
    'endorsement is implied. Two-view partial arc with flash lighting and estimated 88mm\n'
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
    'selection': 'Fixed pair 10/11 selected after separate native preflight; not a held-out benchmark.',
    'privacy': 'No original JPEGs, raw EXIF, camera serials, owner/contact data or import databases retained.',
    'scope': 'REAL_LICENSED_STEREO_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY',
})

LICENSE_README_BYTES = b'![Preview of Images](./Preview.jpg)\n\n## [More Photogrammetry Test Sets](https://github.com/AlansCodeLog/photogrammetry-test-sets)\n\n## License\n<a rel="license" href="http://creativecommons.org/licenses/by/4.0/"><img alt="Creative Commons License" style="border-width:0" src="https://i.creativecommons.org/l/by/4.0/88x31.png" /></a><br />This work is licensed under a <a rel="license" href="http://creativecommons.org/licenses/by/4.0/">Creative Commons Attribution 4.0 International License</a>\n\n### Attribution\n```\n"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background" by alansartlog / CC BY 4.0\n```\n\nIf using a specific image, append "# [NUMBER]" to the title.\n\nIf you can use links, please link:\n- The title to the corresponding repo or the main repo (https://github.com/AlansCodeLog/photogrammetry-test-sets).\n- alansartlog => "https://alansartlog.com"\n- CC BY 4.0 => "https://creativecommons.org/licenses/by/4.0/"\n\nHere\'s the markdown version:\n\n```\n["Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background"](https://github.com/AlansCodeLog/photogrammetry-test-sets) by [alansartlog](https://alansartlog.com) / [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)\n```'
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

def fresh_directory(destination):
    destination = Path(destination)
    require(destination.is_absolute() and not destination.exists() and not destination.is_symlink(), 'fresh output required')
    canonical_directory(destination.parent)
    destination.mkdir(mode=0o700)
    return canonical_directory(destination)

def fetch(run, destination):
    """CI-only three fixed public files. No redirects, retries, caller URLs or output-byte logs."""
    require(run.env.get('GITHUB_ACTIONS') == 'true' and
            run.env.get('GITHUB_REPOSITORY') == 'cheshmakzanoon-ops/3d-scanner', 'licensed input fetch is CI preparation only')
    require(Path(destination) == canonical_directory(run.work) / 'licensed-source', 'fixed owned download destination required')
    destination = fresh_directory(destination)
    # README first: refuse mismatched license provenance before downloading photographs.
    for name in ('README.md', '10.JPG', '11.JPG'):
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
    require({p.name for p in images.iterdir()} == set(HASHES), 'exact two image inventory required')
    require(bounded_file(root / 'observation-inputs.json', len(MANIFEST_BYTES)) == MANIFEST_BYTES, 'fixed observation manifest changed')
    for name, expected in HASHES.items():
        data = bounded_file(images / name, SIZES[name])
        require(digest(data) == expected, 'fixed JPEG checksum')
        require(jpeg_dimensions(data) == (4272, 2848), 'fixed JPEG dimensions')
    return {'schema': 1, 'manifestSha256': digest(MANIFEST_BYTES),
            'images': {'images/' + name: value for name, value in HASHES.items()}, 'policySha256': digest(POLICY_BYTES)}

def write_fresh(path, data):
    path = Path(path); canonical_directory(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as output:
            output.write(data)
    finally:
        os.close(fd)

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

# Existing repository host-tools Pillow pin, used only for a private in-memory leak denylist.
PILLOW_WHEEL = 'pillow-13.0.0-cp312-cp312-manylinux_2_27_x86_64.manylinux_2_28_x86_64.whl'
PILLOW_SHA256 = '42d63be3af4f9b951b199b0a9d23365d37ab14687e6d77b74940432c8515c4ca'
PILLOW_BYTES = 7144542
PILLOW_URL = 'https://files.pythonhosted.org/packages/e6/99/fdf65cc539e266ab93546c261bdf98411fe165c6e4e13f9dbf597097192b/' + PILLOW_WHEEL
_PRIVACY_READER = None
_PRIVACY_FILES = None
_PRIVACY_ROOT = None


def _verify_privacy_reader():
    import sys
    require(_PRIVACY_READER is not None and _PRIVACY_FILES is not None and _PRIVACY_ROOT is not None,
            'pinned private metadata reader not prepared')
    for name, module in tuple(sys.modules.items()):
        if name == 'PIL' or name.startswith('PIL.'):
            path = Path(getattr(module, '__file__', '/missing'))
            require(path in _PRIVACY_FILES and path.is_relative_to(_PRIVACY_ROOT), 'foreign metadata reader module')
            expected_size, expected_sha = _PRIVACY_FILES[path]
            require(digest(bounded_file(path, expected_size)) == expected_sha, 'metadata reader module changed')
    require(sys.modules['PIL'].__version__ == '13.0.0', 'metadata reader version mismatch')


def prepare_privacy_reader(run):
    """Prepare the one repository-pinned official wheel; no global installation or private output."""
    import importlib
    import sys
    import zipfile
    global _PRIVACY_READER, _PRIVACY_FILES, _PRIVACY_ROOT
    require(sys.version_info[:2] == (3, 12), 'pinned metadata reader requires Python 3.12')
    require(not any(n == 'PIL' or n.startswith('PIL.') for n in sys.modules), 'metadata reader already imported')
    root = fresh_directory(canonical_directory(run.work) / 'privacy-reader')
    wheel = root / PILLOW_WHEEL
    raw, code = run.command('licensed-private-reader-download', ['curl', '-q', '--fail', '--silent', '--show-error',
        '--proto', '=https', '--max-time', '60', '--max-filesize', str(PILLOW_BYTES),
        '--output', str(wheel), '--write-out', '%{http_code}', '--', PILLOW_URL], seconds=60, limit=4096)
    require(code == 0 and raw == b'200', 'metadata reader download failed')
    require(digest(bounded_file(wheel, PILLOW_BYTES)) == PILLOW_SHA256, 'metadata reader wheel mismatch')
    files = {}; total = 0
    with zipfile.ZipFile(wheel) as archive:
        entries = archive.infolist()
        require(0 < len(entries) <= 4096, 'metadata reader wheel entry bound')
        names = set()
        for entry in entries:
            run.remaining(10)
            name = entry.filename; relative = Path(name)
            require(name not in names and not relative.is_absolute() and '..' not in relative.parts and
                    relative.as_posix() == name.rstrip('/') and '\\' not in name and
                    relative.parts[0] in ('PIL', 'pillow.libs', 'pillow-13.0.0.dist-info'), 'metadata reader wheel path')
            names.add(name)
            mode = entry.external_attr >> 16
            require(not stat.S_ISLNK(mode) and (not stat.S_IFMT(mode) or stat.S_ISREG(mode) or stat.S_ISDIR(mode)),
                    'metadata reader wheel file type')
            target = root / relative
            if entry.is_dir(): target.mkdir(parents=True, exist_ok=True); continue
            total += entry.file_size
            require(0 <= entry.file_size <= 32 * 1024**2 and total <= 64 * 1024**2, 'metadata reader wheel expanded bound')
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as stream:
                data = stream.read(entry.file_size + 1)
                require(len(data) == entry.file_size and not stream.read(1), 'metadata reader wheel length')
            write_fresh(target, data)
            files[target] = (len(data), digest(data))
    sys.path.insert(0, str(root))
    try:
        _PRIVACY_ROOT = root; _PRIVACY_FILES = files
        _PRIVACY_READER = importlib.import_module('PIL.Image')
        _verify_privacy_reader()
    except BaseException:
        _PRIVACY_READER = None; _PRIVACY_FILES = None; _PRIVACY_ROOT = None
        raise ValueError('pinned private metadata reader preparation failed') from None
    return {'version': '13.0.0', 'wheelSha256': PILLOW_SHA256, 'wheelBytes': PILLOW_BYTES}


def _marker_variants(values):
    """No value interpolation in exceptions, logs or serialized outputs."""
    import html
    markers = set()
    def add(raw):
        if not raw: return
        require(type(raw) is bytes and 1 <= len(raw) <= 16 * 1024, 'private marker size bound')
        markers.add(raw)
        require(len(markers) <= 128 and sum(map(len, markers)) <= 64 * 1024, 'private marker aggregate bound')
    for value, encoding in values:
        if isinstance(value, bytes):
            if encoding == 'opaque': add(value); continue
            if encoding == 'comment':
                if value.startswith(b'ASCII\x00\x00\x00'): value = value[8:]; encoding = 'ascii'
                elif value.startswith(b'UNICODE\x00'): value = value[8:]; encoding = 'utf-16'
                else: encoding = 'utf-8'
            try: value = value.decode(encoding, errors='strict')
            except UnicodeError:
                add(value); continue
        require(isinstance(value, str), 'private metadata scalar type')
        value = value.strip('\x00 \t\r\n')
        if not value: continue
        # No length-based exemptions: even a short nonempty private value fails closed.
        for candidate in (value, json.dumps(value, ensure_ascii=True)[1:-1],
                          json.dumps(value, ensure_ascii=False)[1:-1], html.escape(value, quote=True),
                          html.escape(value, quote=False)):
            add(candidate.encode('utf-8'))
    return tuple(sorted(markers))


def private_markers(source):
    """Read private original EXIF into memory only; original bytes remain unchanged."""
    import io
    _verify_privacy_reader()
    buffers = validate_source(source)
    values = []
    try:
        for name in HASHES:
            with _PRIVACY_READER.open(io.BytesIO(buffers[name])) as image:
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
        _verify_privacy_reader()
        return result
    except BaseException:
        raise ValueError('private original metadata inspection failed') from None
