"""Version-one local corpus validator; all file identities are content hashes."""
from __future__ import annotations
import hashlib
import json
import math
from pathlib import Path, PurePosixPath


class InvalidManifest(ValueError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(ok, message):
    if not ok:
        raise InvalidManifest(message)


def _file(item, root, seen):
    require(isinstance(item, dict), 'file must be an object')
    name, digest = item.get('path'), item.get('sha256')
    require(isinstance(name, str) and bool(name) and '\\' not in name, 'invalid file path')
    path = PurePosixPath(name)
    require(not path.is_absolute() and '..' not in path.parts, 'file path escapes dataset root')
    require(isinstance(digest, str) and len(digest) == 64 and all(c in '0123456789abcdef' for c in digest), 'invalid SHA256')
    require(name not in seen, 'duplicate file path')
    seen.add(name)
    local = (root / name).resolve()
    require(local.is_relative_to(root.resolve()), 'symlink escapes dataset root')
    require(local.is_file(), 'missing local input: ' + name)
    require(sha256(local) == digest, 'tampered input: ' + name)
    return local


def validate(manifest: dict, root: Path) -> dict:
    require(isinstance(manifest, dict), 'manifest must be an object')
    require(manifest.get('schema_version') == 1, 'unsupported schema_version')
    require(isinstance(manifest.get('dataset_id'), str) and bool(manifest['dataset_id'].strip()), 'missing dataset_id')
    kind, split = manifest.get('kind'), manifest.get('split')
    require(kind in ('synthetic', 'real'), 'unknown dataset kind')
    require(split in ('regression', 'development', 'held_out'), 'unknown split')
    require(kind != 'synthetic' or split == 'regression', 'synthetic data must remain regression')
    require(manifest.get('scene') in ('matte_textured', 'thin', 'flat', 'glossy', 'transparent', 'low_texture', 'moving'), 'missing scene difficulty')
    license = manifest.get('permission', {})
    require(isinstance(license, dict), 'permission must be an object')
    require(isinstance(manifest.get('capture'), dict), 'capture must be an object')
    require(all(isinstance(license.get(k), str) and bool(license[k].strip()) for k in ('license', 'source', 'authorization', 'provenance')), 'missing permission/provenance')
    require(license.get('evaluation') is True, 'evaluation not authorized')
    require(isinstance(license.get('redistribution'), bool), 'redistribution permission must be explicit')
    require(manifest.get('units') in ('m', 'mm', 'arbitrary'), 'invalid units')
    require(isinstance(manifest.get('metric_scale_verified'), bool), 'missing scale verification')
    require(manifest['units'] != 'arbitrary' or not manifest['metric_scale_verified'], 'arbitrary scale cannot be verified metric')
    photos = manifest.get('photos')
    require(isinstance(photos, list) and len(photos) >= 2, 'at least two photographs required')
    seen, hashes, ids = set(), set(), set()
    for photo in photos:
        local_photo = _file(photo, root, seen)
        require(photo['sha256'] not in hashes, 'duplicate photo content')
        hashes.add(photo['sha256'])
        require(isinstance(photo.get('id'), str) and photo['id'] and photo['id'] not in ids, 'duplicate/missing photo ID')
        ids.add(photo['id'])
        require(photo.get('role') in ('reconstruction', 'held_out'), 'missing photo role')
        require(all(type(photo.get(k)) is int and photo[k] > 0 for k in ('width', 'height')), 'invalid image dimensions')
        from PIL import Image, UnidentifiedImageError
        try:
            with Image.open(local_photo) as decoded:
                require(list(decoded.size) == [photo['width'], photo['height']], 'decoded image dimensions mismatch')
                decoded.verify()
        except (OSError, UnidentifiedImageError) as exc:
            raise InvalidManifest('invalid photograph bytes') from exc
        require(photo.get('lens_id') == manifest.get('capture', {}).get('lens_id'), 'inconsistent capture lens')
    require(sum(p['role'] == 'reconstruction' for p in photos) >= 2, 'at least two reconstruction inputs required')
    capture = manifest.get('capture', {})
    require(all(isinstance(capture.get(k), str) and capture[k].strip() for k in ('device', 'lens_id', 'settings')), 'missing capture settings')
    calibration = manifest.get('calibration', {})
    require(isinstance(calibration, dict), 'calibration must be an object')
    calibration_path = _file(calibration, root, seen)
    require(calibration.get('lens_id') == capture['lens_id'], 'inconsistent calibration lens')
    require(calibration.get('provenance'), 'missing calibration provenance')
    try:
        calibration_data = json.loads(calibration_path.read_text())
        require(calibration_data.get('lens_id') == capture['lens_id'], 'calibration content lens mismatch')
        require(calibration_data.get('image_size') == [photos[0]['width'], photos[0]['height']], 'calibration image size mismatch')
        require(all([p['width'], p['height']] == calibration_data['image_size'] for p in photos), 'inconsistent photo/calibration resolution')
        k = calibration_data['K']
        require(len(k) == 3 and all(len(row) == 3 for row in k) and all(type(v) in (float, int) and math.isfinite(v) for row in k for v in row), 'invalid calibration matrix')
        require(k[0][0] > 0 and k[1][1] > 0 and k[2] == [0, 0, 1], 'invalid calibration intrinsics')
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
        raise InvalidManifest('invalid calibration content') from exc
    reference = manifest.get('reference', {})
    require(isinstance(reference, dict), 'reference must be an object')
    _file(reference, root, seen)
    require(reference.get('provenance') and reference.get('method'), 'unknown reference provenance')
    require(reference.get('independent') is True, 'reference is not independent')
    require(reference.get('units') == manifest['units'], 'reference units mismatch')
    require(reference.get('method') != 'candidate_reconstruction', 'candidate cannot be its own reference')
    if kind == 'real':
        require(reference['method'] not in ('analytic', 'synthetic'), 'generated reference cannot qualify physical data')
        require(capture.get('physical') is True, 'real capture needs physical evidence')
    else:
        require(capture.get('physical') is False, 'synthetic capture mislabeled physical')
    identity = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
    return {'schema_version': 1, 'dataset_id': manifest['dataset_id'], 'manifest_sha256': identity,
            'kind': kind, 'split': split, 'files_verified': len(seen), 'photo_count': len(photos),
            'physical_qualification': 'ELIGIBLE_INPUT_ONLY' if kind == 'real' else 'NOT_APPLICABLE_SYNTHETIC',
            'status': 'PASS'}


def discover(root: Path):
    """Find local manifests only; no upload, recursive symlink traversal or network."""
    return sorted(p for p in root.rglob('manifest.json') if p.resolve().is_relative_to(root.resolve()))


def audit_corpus(paths):
    receipts, identities, photo_owners = [], set(), {}
    for path in paths:
        manifest = json.loads(path.read_text())
        receipt = validate(manifest, path.parent)
        require(receipt['dataset_id'] not in identities, 'duplicate dataset ID across corpus')
        identities.add(receipt['dataset_id'])
        for photo in manifest['photos']:
            owner = (manifest['split'], photo['role'])
            previous = photo_owners.get(photo['sha256'])
            require(previous is None or previous == owner, 'held-out/development photo leakage across corpus')
            photo_owners[photo['sha256']] = owner
        receipts.append(receipt)
    return receipts
