"""Reproduce actual061–064 writer outputs from immutable local Git snapshots.

No provider code is merged into090. Requires fetched Git objects, g++, pytest,
OpenCV plus the independent tools documented in the interoperability protocol.
The output must be a new directory outside the checkout.
"""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET
from .validate import validate

SOURCES = {'061': '04f53572b44e41d02da1425b2163d2f7eae568d2', '062': 'f733056010b60c979f6d9915e8b67c35aa79cac7', '063': '63699d4c1396d2f3d91c74d7f80c52c3f99e9fc0', '064': '1baaaa23087f82e3efca29b363714d4e77ed94f7'}


def run(root, output):
    if output.resolve().is_relative_to(root.resolve()):
        raise ValueError('Keep provider snapshots/artifacts outside checkout')
    output.mkdir(parents=True, exist_ok=False)
    for agent, sha in SOURCES.items():
        snapshot = output / agent
        snapshot.mkdir()
        archive = subprocess.check_output(['git', '-C', str(root), 'archive', sha])
        with tarfile.open(fileobj=io.BytesIO(archive)) as packed:
            packed.extractall(snapshot, filter='data')
    env = dict(os.environ, RF_CANONICAL_ASSET_ROOT=str(output / '061'))
    test_files = ['061/tests/test_agent_061_asset_contract.py', '062/tests/test_agent_062_obj.py', '063/tests/test_agent_063_stl.py', '064/tests/test_agent_064_ply.py']
    native = subprocess.run([sys.executable, '-m', 'pytest', '-q', '--basetemp=' + str(output / 'native-fixtures'), '--junitxml=' + str(output / 'native-tests.xml')] + [str(output / name) for name in test_files], env=env, capture_output=True, text=True, timeout=180)
    (output / 'native-tests.txt').write_text(native.stdout + native.stderr)
    xml = ET.parse(output / 'native-tests.xml')
    tests = len(xml.findall('.//testcase'))
    if native.returncode or tests != 7:
        raise RuntimeError('Actual writer regression failed/incomplete; see retained native-tests.txt')
    fixtures = output / 'native-fixtures'
    assets = [('061', next(fixtures.rglob('texture.glb')), {'triangles': 1, 'textured_triangles': 1, 'bounds': [[-2, -5, -3], [4, -1, -3]], 'texture_samples': [{'point': [-2, -1, -3], 'rgb': [255, 0, 0]}, {'point': [4, -1, -3], 'rgb': [0, 255, 0]}, {'point': [-2, -5, -3], 'rgb': [0, 0, 255]}]}),
              ('062', next(p for p in fixtures.rglob('asymmetric.obj') if p.parent.name == 'different-directory'), {'triangles': 2, 'textured_triangles': 2, 'bounds': [[0, -3, -4], [2, 0, 0]], 'texture_samples': [{'point': [0, 0, 0], 'rgb': [255, 0, 0]}, {'point': [2, 0, 0], 'rgb': [0, 255, 0]}, {'point': [0, -3, 0], 'rgb': [0, 0, 255]}, {'point': [0, 0, -4], 'rgb': [255, 255, 255]}]}),
              ('063', next(fixtures.rglob('asymmetric.stl')), {'triangles': 2, 'bounds': [[0, -30, -40], [20, 0, 0]]}),
              ('064', next(fixtures.rglob('surface.ply')), {'triangles': 1, 'bounds': [[0, -4, -3], [3, 0, -1]]})]
    reports = []
    for agent, asset, expected in assets:
        report = validate(asset, expected, target_tool=True)
        report.update(provider=agent, provider_source_sha=SOURCES[agent])
        reports.append(report)
    result = {'schema': 1, 'data_kind': 'synthetic_analytic_writer_fixtures', 'sources': SOURCES, 'native_tests': tests, 'native_status': 'PASS', 'assets': reports, 'status': 'PASS' if all(r['status'] == 'PASS' for r in reports) else 'FAIL', 'physical_gate': 'BLOCKED_EXTERNAL'}
    (output / 'provider-matrix.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = run(args.root, args.output)
    print(json.dumps({'status': report['status'], 'native_tests': report['native_tests'], 'assets': {r['provider']: r['status'] for r in report['assets']}}))
    raise SystemExit(0 if report['status'] == 'PASS' else 1)
