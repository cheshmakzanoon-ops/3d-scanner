"""Adapt full084/085 per-scene reports without promoting limited/synthetic gates."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from .aggregate import load_json, require, digest


def adapt(gate, raw, candidate, environment, recorded_at, command, evidence_sha256):
    require(gate in ('geometry', 'texture') and isinstance(raw.get('scenes'), list) and raw['scenes'], 'full per-scene quality report required; limited UV reports are insufficient')
    records = []
    for scene in raw['scenes']:
        metrics, blockers = scene.get('metrics', {}), list(scene.get('failures', []))
        status = scene.get('status', 'NOT_RUN')
        if status not in ('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL'):
            status, blockers = 'FAIL', blockers + ['unsupported raw scene status']
        if raw.get('physical_gate') != 'PASS' and status != 'FAIL':
            status, blockers = 'BLOCKED_EXTERNAL', blockers + ['raw quality report does not pass physical gate']
        source = scene.get('candidate_source_sha')
        if source != candidate['source_sha']:
            status, blockers = 'FAIL', blockers + ['raw measured candidate source differs from frozen candidate']
        selected = next((s for s in candidate['scenes'] if s['id'] == scene['id']), None)
        if selected is None or selected['sha256'] != scene.get('manifest_sha256'):
            status, blockers = 'FAIL', blockers + ['raw scene manifest differs from frozen authorized scene']
        counts = {'executed_tests': 1, 'failed_tests': 1 if status == 'FAIL' else 0, 'skipped_tests': 0, 'disabled_tests': 0}
        if gate == 'geometry':
            topo = metrics.get('topology', {})
            observed = {'registration_fraction': metrics.get('cameras', {}).get('registration_fraction', -1), 'accuracy_p95': metrics.get('accuracy_p95', -1), 'completeness_fraction': metrics.get('completeness_fraction', -1), 'duplicate_triangles': topo.get('duplicate_triangles', -1), 'nonmanifold_edges': topo.get('nonmanifold_edges', -1), 'self_intersection_pairs': topo.get('self_intersection_pairs', -1)}
            if topo.get('self_intersections_status') != 'PASS_CHECK_EXECUTED' and status == 'PASS':
                status, blockers = 'NOT_RUN', blockers + ['self-intersection assessment not executed']
        else:
            held = scene.get('held_out', [])
            checked = scene.get('held_out_status') == 'CHECKED' and held and all(h.get('metrics', {}).get('eligible_reference_pixels', 0) > 0 and h.get('metrics', {}).get('compared_pixels', 0) > 0 for h in held)
            seam = 0 if metrics.get('potential_seam_edges') == 0 else metrics.get('seam_linear_rgb_abs_p95', -1)
            observed = {'supported_face_fraction_original': metrics.get('supported_face_fraction_original', -1), 'held_out_support_fraction': min((h.get('metrics', {}).get('held_out_support_fraction', -1) for h in held), default=-1), 'held_out_linear_rgb_rmse': max((h.get('metrics', {}).get('linear_rgb_rmse', -1) for h in held), default=-1), 'seam_linear_rgb_abs_p95': seam, 'uv_overlap_pairs': metrics.get('uv_overlap_pairs', -1), 'opaque_unknown_pixels': metrics.get('alpha', {}).get('opaque_unknown_pixels', -1), 'anisotropy_p95': metrics.get('distortion_anisotropy_p95', -1)}
            if not checked and status == 'PASS':
                status, blockers = 'NOT_RUN', blockers + ['independent held-out photographic comparison not executed']
            if 'alpha' not in metrics and status == 'PASS':
                status, blockers = 'NOT_RUN', blockers + ['independent atlas support/unknown-alpha assessment not executed']
        data = {'id': scene['id'], 'kind': 'physical' if selected else 'synthetic', 'sha256': scene.get('manifest_sha256', '0'*64), 'reference_sha256': selected['reference_sha256'] if selected else '0'*64}
        if gate == 'geometry' and selected and scene.get('reference_sha256') != selected['reference_sha256']:
            status, blockers = 'FAIL', blockers + ['independent reference geometry hash mismatch']
        # Missing measurements remain invalid sentinels on nonpassing receipts;
        # they cannot accidentally meet a positive/zero numerical criterion.
        observed = {key: value if type(value) in (int, float) and math.isfinite(value) else -1 for key, value in observed.items()}
        record = {'schema': 1, 'agent': '084' if gate == 'geometry' else '085', 'gate': gate, 'source_sha': source if digest(source, 40) else '0'*40, 'artifact_sha256': candidate['artifact_sha256'], 'run_id': candidate['run_id'], 'recorded_at': recorded_at, 'command': command, 'environment': environment, 'dataset_identity': data, 'status': status, 'blockers': blockers, 'metrics': {**counts, **observed}, 'evidence_sha256': evidence_sha256, 'tool_source_sha': raw['source_sha'], 'protocol_sha256': raw['protocol_sha256']}
        records.append(record)
    return records


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gate', choices=('geometry', 'texture'), required=True)
    parser.add_argument('--raw-report', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--environment', type=Path, required=True)
    parser.add_argument('--recorded-at', required=True, help='Actual execution UTC timestamp, not adaptation time')
    parser.add_argument('--command', required=True, help='Exact original measurement invocation')
    parser.add_argument('--records', type=Path, required=True)
    parser.add_argument('--evidence-root', type=Path, required=True)
    args = parser.parse_args()
    raw_bytes = args.raw_report.read_bytes()
    sha = hashlib.sha256(raw_bytes).hexdigest()
    reports = adapt(args.gate, load_json(args.raw_report), load_json(args.candidate), load_json(args.environment), args.recorded_at, args.command, sha)
    args.records.mkdir(parents=True, exist_ok=True)
    args.evidence_root.mkdir(parents=True, exist_ok=True)
    (args.evidence_root / sha).write_bytes(raw_bytes)
    for index, report in enumerate(reports):
        (args.records / (args.gate + '-' + str(index) + '.json')).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'records': len(reports), 'statuses': [r['status'] for r in reports]}))
