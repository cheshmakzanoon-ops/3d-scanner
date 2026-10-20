"""Wrap the app's original lens profile without changing pixels or coordinates."""
import argparse
import json
import math
from pathlib import Path
from PIL import Image

from .manifest import InvalidManifest, require, sha256

CONVERSION = 'app-lens-profile-v1-exif-identity'


def convert(profile: Path, lens_id: str, photos, root: Path):
    root = root.resolve()
    source = profile.resolve()
    require(source.is_relative_to(root) and source.is_file(), 'profile must stay inside corpus root')
    require(isinstance(lens_id, str) and bool(lens_id.strip()), 'explicit lens ID required')
    with source.open('rb') as stream:
        data = stream.read(10001)
    require(len(data) <= 10000, 'app profile exceeds 10000-byte budget')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate profile field')
            result[key] = value
        return result
    try:
        original = json.loads(data, object_pairs_hook=unique)
    except (ValueError, UnicodeError) as exc:
        raise InvalidManifest('invalid app profile JSON') from exc
    require(isinstance(original, dict), 'app profile must be an object')
    require(type(original.get('schema')) is int and original['schema'] == 1
            and original.get('model') == 'brown-conrady-5', 'unsupported app lens profile')
    require(original.get('orientation') == 'exif-upright', 'profile frame must be exif-upright')
    require(all(type(original.get(k)) is int and original[k] > 0 for k in ('width','height')), 'invalid profile dimensions')
    fields = ('fx','fy','cx','cy','k1','k2','p1','p2','k3')
    require(all(type(original.get(k)) in (int,float) and math.isfinite(original[k]) for k in fields), 'invalid profile intrinsics/distortion')
    require(original['fx'] > 0 and original['fy'] > 0, 'invalid profile focal length')
    require(bool(photos), 'photographs required for frame validation')
    size = [original['width'], original['height']]
    for photo in photos:
        path = photo.resolve()
        require(path.is_relative_to(root) and path.is_file(), 'photo must stay inside corpus root')
        with Image.open(path) as image:
            require(image.getexif().get(274, 1) == 1,
                    'nonidentity EXIF is unsupported; preserve originals and request027/086 coordinate adapter')
            require(list(image.size) == size, 'profile/photo raw dimensions differ; no implicit scaling or rotation')
        with Image.open(path) as image:
            image.verify()
    return {'lens_id': lens_id, 'image_size': size,
            'K': [[original['fx'],0,original['cx']],[0,original['fy'],original['cy']],[0,0,1]],
            'distortion': {'model':'brown-conrady-5', **{k:original[k] for k in ('k1','k2','p1','p2','k3')}},
            'coordinate_frame': 'raw-pixels-exif-identity', 'conversion': CONVERSION,
            'source_profile': {'path':source.relative_to(root).as_posix(), 'sha256':sha256(source),
                               'orientation':'exif-upright'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', type=Path)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--lens-id', required=True)
    parser.add_argument('--photo', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        wrapped = convert(args.profile, args.lens_id, args.photo, args.root)
        output = args.output.resolve()
        require(output.is_relative_to(args.root.resolve()), 'output must stay inside corpus root')
        require(not args.output.is_symlink() and not output.exists(), 'refuse overwriting any original/input')
        with output.open('x') as stream:
            stream.write(json.dumps(wrapped, indent=2, allow_nan=False)+'\n')
    except (InvalidManifest, OSError, ValueError) as exc:
        print(json.dumps({'status':'FAIL','error':str(exc)}))
        return 1
    print(json.dumps({'status':'PASS_INPUT_CONVERSION_ONLY','sha256':sha256(output)}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
