"""Known-camera kernel baseline only: PNGs+camera rows -> unchanged NativeEngine.surface."""
import argparse, hashlib, json, sys, time, subprocess
from pathlib import Path
import cv2, numpy as np

def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def pins(repo):
    return {str(p.relative_to(repo)): sha(p) for p in sorted(repo.rglob('*')) if p.is_file() and (str(p.relative_to(repo)).startswith('reconstruction/') or p == repo / 'CMakeLists.txt' or p == repo / 'tools/native_api.py')}

def main():
    a = argparse.ArgumentParser()
    a.add_argument('--repo', type=Path, required=True)
    a.add_argument('--library', type=Path, required=True)
    a.add_argument('--fixture', type=Path, required=True)
    a.add_argument('--output', type=Path, required=True)
    args = a.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    receipt = {
        'schema': 1,
        'status': 'FAILED',
        'synthetic': True,
        'arm': 'known-camera CPU kernel',
        'productionControllerParity': False,
        'boundary': 'NativeEngine.surface -> rf_surface_json -> reconstructDense(default settings) -> fuseDepthSurfaces(default settings); not Kotlin/controller/coarse-to-fine/Android execution',
        'truthPassedToSolver': False,
    }
    try:
        repo = args.repo.resolve()
        lib = args.library.resolve()
        sys.path.insert(0, str(repo / 'tools'))
        from native_api import NativeEngine
        manifest = json.loads((args.fixture / 'fixture.json').read_text())
        photos = sorted(args.fixture.glob('view-*.png'))
        if len(photos) != 8:
            raise ValueError('Exactly 8 synthetic PNG views required')
        decoded = [cv2.imread(str(p), cv2.IMREAD_UNCHANGED) for p in photos]
        expected = (manifest['height'], manifest['width'], 4)
        if any((p is None or p.shape != expected or p.dtype != np.uint8 for p in decoded)):
            raise ValueError('Input RGBA dimensions/type do not match manifest')
        images = np.asarray([cv2.cvtColor(p, cv2.COLOR_BGRA2RGBA) for p in decoded])
        cameras = np.asarray(manifest['cameras'], dtype=float)
        s = manifest['settings']
        if cameras.shape != (8, 16) or not np.all(np.isfinite(cameras)):
            raise ValueError('Expected finite 8x16 known-camera rows')
        before = pins(repo)
        libhash = sha(lib)
        inputs = {p.name: sha(p) for p in [args.fixture / 'fixture.json', *photos]}
        receipt.update({
            'imageOnlyEstimatedCameraArm': manifest['imageOnlyArm'],
            'settings': s,
            'librarySHA256': libhash,
            'inputsSHA256': inputs,
            'sourceSHA256': before,
            'scriptsSHA256': {p.name: sha(p) for p in Path(__file__).parent.glob('*.py')},
            'sourceCommit': subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip(),
            'versions': {
                'python': sys.version,
                'numpy': np.__version__,
                'opencv': cv2.__version__,
            },
        })
        engine = NativeEngine(lib)
        mapped = [line for line in Path('/proc/self/maps').read_text().splitlines() if str(lib) in line]
        if not mapped:
            raise RuntimeError('Requested native library not present in actual process maps')
        receipt['runtimeLibraryMappings'] = mapped
        start = time.perf_counter()
        raw = engine.surface(images, cameras, s['near'], s['far'], s['planes'], s['stride'])
        receipt['nativeWallSeconds'] = time.perf_counter() - start
        (args.output / 'native-result.json').write_text(json.dumps(raw, separators=(',', ':')) + '\n')
        receipt['rawResultSHA256'] = sha(args.output / 'native-result.json')
        receipt['nativeStatus'] = 'MESH_FAILED' if 'meshError' in raw else 'COMPLETED'
        if 'meshError' in raw:
            receipt['meshError'] = raw['meshError']
            v = np.empty((0, 3))
            t = np.empty((0, 3), int)
        else:
            v = np.asarray(raw['mesh']['vertices'], float).reshape(-1, 11)[:, :3]
            t = np.asarray(raw['mesh']['triangles'], int).reshape(-1, 3)
        receipt['meshDiagnostics'] = {k: v for k, v in raw.get('mesh', {}).items() if k not in ['vertices', 'triangles']}
        receipt['vertices'] = len(v)
        receipt['triangles'] = len(t)
        receipt['dense'] = {k: v for k, v in raw['dense'].items() if k not in ['points', 'depthMaps', 'views'] and (not isinstance(v, dict))}
        from evaluate import evaluate
        receipt['evaluation'] = evaluate(manifest, v, t)
        receipt['sourcesUnchanged'] = before == pins(repo)
        receipt['libraryUnchanged'] = libhash == sha(lib)
        receipt['inputsUnchanged'] = inputs == {p.name: sha(p) for p in [args.fixture / 'fixture.json', *photos]}
        if not all((receipt[k] for k in ['sourcesUnchanged', 'libraryUnchanged', 'inputsUnchanged'])):
            raise RuntimeError('Provenance changed during execution')
        receipt['status'] = 'ASSESSMENT_COMPLETED_NOT_QUALITY_PASS'
    except Exception as e:
        receipt['status'] = 'FAILED'
        receipt['error'] = repr(e)
    (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2, allow_nan=False) + '\n')
    print(json.dumps({k: v for k, v in receipt.items() if k not in ['sourceSHA256', 'dense']}, indent=2))
    return 0 if receipt['status'] == 'ASSESSMENT_COMPLETED_NOT_QUALITY_PASS' else 1
if __name__ == '__main__':
    sys.exit(main())
