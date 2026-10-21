#!/usr/bin/env python3
"""Independent analytic, explicitly synthetic two-view numerical oracle."""
import argparse
import json
import sys
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools'))
from native_api import NativeEngine


def finite(value):
    if isinstance(value, dict):
        return all(finite(v) for v in value.values())
    if isinstance(value, list):
        return all(finite(v) for v in value)
    return not isinstance(value, (int, float)) or bool(np.isfinite(value))


def validate(scene):
    if not finite(scene):
        raise ValueError('Nonfinite numerical result')
    exact = scene['noise'] == 0
    if scene['point_count'] < (170 if exact else 148):
        raise ValueError('Insufficient retained observations')
    if scene['rotation_error'] >= (1e-5 if exact else .035):
        raise ValueError('Rotation contract failed')
    if exact and scene['median_position_error'] >= 1e-5:
        raise ValueError('Analytic position contract failed')


def run():
    engine = NativeEngine()
    scenes = []
    for seed in (1, 7, 19, 82, 131):
        for noise, outliers in ((0., 0), (.00018, 20)):
            rng = np.random.default_rng(seed)
            xyz = np.column_stack((rng.uniform(-1, 1, (180, 2)), rng.uniform(3, 8, 180)))
            a = .08
            rotation = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])
            second = xyz @ rotation.T + [-1, 0, 0]
            first_uv = xyz[:, :2] / xyz[:, 2, None] + rng.normal(0, noise, (180, 2))
            second_uv = second[:, :2] / second[:, 2, None] + rng.normal(0, noise, (180, 2))
            second_uv[:outliers] = rng.uniform(-.5, .5, (outliers, 2))
            rows = np.column_stack((first_uv, second_uv, rng.integers(0, 256, (180, 3))))
            output = engine.reconstruct(rows)
            repeated = engine.reconstruct(rows)
            if output != repeated:
                raise ValueError(f'Nondeterministic output for seed {seed}, noise {noise}')
            points = np.asarray(output['points'])
            ids = points[:, 8].astype(int)
            scene = dict(seed=seed, noise=noise, outliers=outliers, point_count=len(points),
                         rotation_error=float(np.linalg.norm(np.asarray(output['rotation']).reshape(3, 3) - rotation)),
                         median_position_error=float(np.median(np.linalg.norm(points[:, :3] - xyz[ids], axis=1))),
                         points=points.tolist(), rotation=output['rotation'], translation=output['translation'])
            try:
                validate(scene)
            except ValueError as error:
                raise ValueError(f'seed={seed}, noise={noise}: {error}; metrics={scene}') from error
            scenes.append(scene)
    return dict(schema=1, fixture='synthetic analytic two-view; no physical capture', scenes=scenes)


def compare(reference, candidate):
    if len(reference['scenes']) != len(candidate['scenes']):
        raise ValueError('Scene count changed')
    for a, b in zip(reference['scenes'], candidate['scenes']):
        validate(a)
        validate(b)
        if (a['seed'], a['noise'], a['outliers']) != (b['seed'], b['noise'], b['outliers']):
            raise ValueError('Scene identity changed')
        for key in ('points', 'rotation', 'translation'):
            if not np.allclose(a[key], b[key], rtol=1e-5, atol=1e-6):
                raise ValueError(f'Compiler parity failed: seed={a["seed"]} noise={a["noise"]} key={key}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--verify', type=Path, help='Validate an external numerical receipt; nonzero on a bad fixture')
    args = parser.parse_args()
    if args.verify:
        receipt = json.loads(args.verify.read_text())
        for scene in receipt['scenes']:
            validate(scene)
    else:
        receipt = run()
    args.output.write_text(json.dumps(receipt, indent=2, allow_nan=False) + '\n')
