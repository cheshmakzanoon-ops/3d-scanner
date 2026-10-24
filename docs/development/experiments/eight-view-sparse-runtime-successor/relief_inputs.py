"""Three pinned observation JPEGs only; never reads calibration, cameras, or evaluation truth."""
import hashlib
import json
import os
import stat
from pathlib import Path

LIMIT = 2 * 1024 * 1024
DATASET = 'existing-relief-three-jpeg-800x600-nominal-no-profile'
INTRINSICS_PROVENANCE = 'NOMINAL_35MM_ESTIMATE_32_88_NO_CALIBRATION_PROFILE'
HASHES = {
    'view-01.jpg': '66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c',
    'view-02.jpg': 'c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b',
    'view-03.jpg': 'a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f',
}
MANIFEST = {'schema': 1, 'dataset': DATASET, 'synthetic': True, 'width': 800, 'height': 600,
            'intrinsicsProvenance': INTRINSICS_PROVENANCE, 'nominalFocal35': 32.88,
            'images': [{'id': name[:-4], 'path': 'images/' + name, 'sha256': digest} for name, digest in HASHES.items()]}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode('ascii')

MANIFEST_BYTES = encoded(MANIFEST)
POLICY_BYTES = encoded({'schema': 1, 'dataset': DATASET, 'manifestSha256': digest(MANIFEST_BYTES),
                        'images': MANIFEST['images'], 'intrinsicsProvenance': INTRINSICS_PROVENANCE,
                        'nominalFocal35': 32.88})

def require(condition, message):
    if not condition:
        raise ValueError(message)

def canonical_directory(path):
    path = Path(path)
    require(path.is_absolute() and path.absolute() == path.resolve(strict=True), 'directory alias/noncanonical path')
    require(stat.S_ISDIR(path.lstat().st_mode) and not path.is_symlink(), 'invalid directory')
    return path

def bounded_file(path):
    path = Path(path)
    require(path.is_absolute() and path.absolute() == path.resolve(strict=True), 'file alias/noncanonical path')
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= LIMIT, 'file type/size bound')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        require((opened.st_dev, opened.st_ino, opened.st_size) == (before.st_dev, before.st_ino, before.st_size), 'file replaced before read')
        blocks = []; total = 0
        while True:
            block = os.read(fd, min(65536, LIMIT + 1 - total))
            if not block: break
            total += len(block); require(total <= LIMIT, 'file grew beyond limit'); blocks.append(block)
        after = os.fstat(fd); current = path.lstat()
        key = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        require(key(before) == key(after) == key(current) and total == before.st_size and not path.is_symlink() and path.absolute() == path.resolve(strict=True), 'file changed during read')
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

def validate(root):
    root = canonical_directory(root)
    require({p.name for p in root.iterdir()} == {'observation-inputs.json', 'images'}, 'unexpected staged entry; truth forbidden')
    images = canonical_directory(root / 'images')
    require({p.name for p in images.iterdir()} == set(HASHES), 'exact three image inventory required')
    require(bounded_file(root / 'observation-inputs.json') == MANIFEST_BYTES, 'fixed observation manifest changed')
    for name, expected in HASHES.items():
        data = bounded_file(images / name)
        require(digest(data) == expected, 'fixed JPEG checksum')
        require(jpeg_dimensions(data) == (800, 600), 'fixed JPEG dimensions')
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
    """Open exactly three literal image paths, never enumerate or copy source siblings."""
    source = canonical_directory(source); destination = Path(destination)
    require(destination.is_absolute() and not destination.exists() and not destination.is_symlink(), 'fresh output required')
    canonical_directory(destination.parent)
    buffers = {}
    for name, expected in HASHES.items():
        data = bounded_file(source / name)
        require(digest(data) == expected and jpeg_dimensions(data) == (800, 600), 'fixed source JPEG differs')
        buffers[name] = data
    destination.mkdir(); (destination / 'images').mkdir()
    for name, data in buffers.items(): write_fresh(destination / 'images' / name, data)
    write_fresh(destination / 'observation-inputs.json', MANIFEST_BYTES)
    return validate(destination)
