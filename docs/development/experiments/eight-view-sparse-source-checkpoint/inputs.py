"""Observation-only boundary. No reconstruction, truth reading, or network access."""
import hashlib, json, shutil, struct, zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIMIT = 2 * 1024 * 1024

def digest(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def parse(data):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            require(k not in result, 'duplicate JSON field')
            result[k] = v
        return result
    return json.loads(data, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))

def bounded_file(path):
    require(not path.is_symlink() and path.is_file(), 'not a regular nonsymlink file')
    require(0 < path.stat().st_size <= LIMIT, 'file size bound')
    return path.read_bytes()

def validate(root):
    root = Path(root)
    require(not root.is_symlink() and root.is_dir(), 'invalid input directory')
    require({p.name for p in root.iterdir()} == {'observation-inputs.json', 'images'}, 'unexpected root entry; truth forbidden')
    image_dir = root / 'images'
    require(not image_dir.is_symlink() and image_dir.is_dir(), 'invalid image directory')
    policy = parse((HERE / 'policy.json').read_bytes())
    raw = bounded_file(root / 'observation-inputs.json')
    m = parse(raw)
    require(set(m) == {'schema','dataset','synthetic','width','height','intrinsicsProvenance','images'}, 'manifest field allowlist')
    require(type(m['schema']) is int and m['schema'] == 1 and m['synthetic'] is True, 'manifest type/schema')
    require(type(m['width']) is int and type(m['height']) is int and (m['width'],m['height']) == (160,120), 'dimensions')
    require(m['dataset'] == policy['dataset'], 'dataset identity')
    require(type(m['images']) is list and len(m['images']) == 8, 'all eight required')
    require({p.name for p in image_dir.iterdir()} == {f'view-{i:02d}.png' for i in range(8)}, 'exact image inventory required')
    for i, item in enumerate(m['images']):
        require(type(item) is dict and set(item) == {'id','path','sha256','K','distortion'}, 'image field allowlist')
        require(item == policy['images'][i], 'fixed image metadata/intrinsics/order changed')
        require(item['id'] == f'view-{i:02d}' and item['path'] == f'images/view-{i:02d}.png', 'canonical path required')
        data = bounded_file(root / item['path'])
        require(digest(data) == item['sha256'], 'image checksum')
        require(data[:8] == b'\x89PNG\r\n\x1a\n', 'PNG signature')
        require(len(data) >= 33 and data[12:16] == b'IHDR', 'PNG IHDR')
        width, height, depth, color, comp, filt, interlace = struct.unpack('>IIBBBBB', data[16:29])
        require((width,height,depth,comp,filt) == (160,120,8,0,0), 'PNG dimensions/encoding')
        # Source bytes are pinned. Runtime OpaqueBitmapBridge additionally checks every alpha pixel.
        require(color in (2,6) and interlace in (0,1), 'PNG RGB/RGBA only')
    require(digest(raw) == policy['manifestSha256'], 'exact manifest bytes changed')
    return {'schema':1,'manifestSha256':digest(raw),'images':{x['path']:x['sha256'] for x in m['images']},'policySha256':digest((HERE/'policy.json').read_bytes())}

def stage(source, destination):
    """Copy only named observation files; NEVER walk or open source evaluation-only."""
    source, destination = Path(source), Path(destination)
    require(not destination.exists(), 'fresh output required')
    # Reject input-root symlink; named files/parent checked before copying.
    require(not source.is_symlink() and source.is_dir(), 'invalid source')
    require(not (source/'images').is_symlink(), 'symlink image parent')
    paths = ['observation-inputs.json'] + [f'images/view-{i:02d}.png' for i in range(8)]
    buffers = {name: bounded_file(source/name) for name in paths}
    destination.mkdir()
    (destination/'images').mkdir()
    for name,data in buffers.items():
        (destination/name).write_bytes(data)
    # Invalid staging is retained for diagnosis, never accepted by return.
    return validate(destination)
