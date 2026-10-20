"""Reject stale/missing/failed candidate gates. Exit0 PASS,1 FAIL,2 blocked.

Receipts are assertions backed by immutable local evidence; hashes establish
identity, not authenticity. No percentage score or public release action exists.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
POLICY_PATH = HERE / 'policy-v1.json'
STATUSES = {'PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL'}
RECORD_FIELDS = {'schema', 'agent', 'gate', 'source_sha', 'artifact_sha256', 'run_id', 'recorded_at', 'command', 'environment', 'dataset_identity', 'status', 'blockers', 'metrics', 'evidence_sha256', 'tool_source_sha', 'protocol_sha256'}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value, length):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{' + str(length) + '}', value) is not None


def timestamp(value):
    require(isinstance(value, str) and value.endswith('Z'), 'timestamp must be UTC Z')
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(parsed.utcoffset() == timedelta(0), 'timestamp timezone')
    return parsed


def load_json(path):
    require(path.stat().st_size <= 4 * 1024 * 1024, 'oversized evidence JSON')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate JSON key')
            result[key] = value
        return result
    def bad(value):
        raise ValueError('nonfinite JSON')
    return json.loads(path.read_text(), object_pairs_hook=unique, parse_constant=bad)


def file_digest(path):
    require(path.is_file() and path.stat().st_size <= 1024 * 1024 * 1024, 'missing/oversized evidence artifact')
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def validate_record(record):
    require(isinstance(record, dict), 'record must be object')
    require(set(record) <= RECORD_FIELDS and RECORD_FIELDS - {'tool_source_sha', 'protocol_sha256'} <= set(record), 'missing/unknown record fields')
    require(type(record['schema']) is int and record['schema'] == 1, 'record schema')
    require(isinstance(record['agent'], str) and re.fullmatch(r'(00[1-9]|0[1-9][0-9]|100)', record['agent']), 'agent identifier')
    require(digest(record['source_sha'], 40) and digest(record['artifact_sha256'], 64) and digest(record['evidence_sha256'], 64), 'record hashes')
    require('tool_source_sha' not in record or digest(record['tool_source_sha'], 40), 'tool source hash')
    require('protocol_sha256' not in record or digest(record['protocol_sha256'], 64), 'protocol hash')
    require(isinstance(record['gate'], str) and record['gate'], 'gate identifier')
    require(isinstance(record['run_id'], str) and 1 <= len(record['run_id']) <= 128, 'run identifier')
    timestamp(record['recorded_at'])
    command = record['command']
    require((isinstance(command, str) and command.strip()) or (isinstance(command, list) and command and all(isinstance(v, str) and v for v in command)), 'empty/invalid command')
    env = record['environment']
    require(isinstance(env, dict) and set(env) <= {'kind', 'identity', 'device_id', 'emulator', 'abi', 'api'} and env.get('kind') in ('host', 'emulator', 'physical') and isinstance(env.get('identity'), str) and env['identity'], 'invalid environment')
    require('emulator' not in env or type(env['emulator']) is bool, 'invalid emulator flag')
    require('api' not in env or type(env['api']) is int and env['api'] >= 29, 'invalid Android API')
    dataset = record['dataset_identity']
    require(isinstance(dataset, dict) and set(dataset) <= {'id', 'kind', 'sha256', 'reference_sha256'} and dataset.get('kind') in ('none', 'synthetic', 'physical') and isinstance(dataset.get('id'), str) and dataset['id'] and digest(dataset.get('sha256'), 64), 'invalid dataset identity')
    require('reference_sha256' not in dataset or digest(dataset['reference_sha256'], 64), 'invalid reference hash')
    require(record['status'] in STATUSES and isinstance(record['blockers'], list) and all(isinstance(v, str) and v for v in record['blockers']), 'invalid status/blockers')
    metrics = record['metrics']
    require(isinstance(metrics, dict) and all(isinstance(k, str) and type(v) in (int, float) and math.isfinite(v) for k, v in metrics.items()), 'metrics must be finite numeric observations')
    for key in ('executed_tests', 'failed_tests', 'skipped_tests', 'disabled_tests'):
        require(type(metrics.get(key)) is int and metrics[key] >= 0, 'missing/invalid executed test counts')
    if record['status'] == 'PASS':
        require(not record['blockers'] and metrics['executed_tests'] > 0 and all(metrics[k] == 0 for k in ('failed_tests', 'skipped_tests', 'disabled_tests')), 'PASS cannot contain blockers, zero tests, failures or skips')
    else:
        require(bool(record['blockers']), 'nonpass requires concrete blocker')


def validate_candidate(candidate, policy):
    require(isinstance(candidate, dict) and type(candidate.get('schema')) is int and candidate.get('schema') == 1, 'candidate schema')
    require(digest(candidate.get('source_sha'), 40) and digest(candidate.get('artifact_sha256'), 64), 'candidate source/APK identity required')
    require(candidate.get('policy_sha256') == hashlib.sha256(POLICY_PATH.read_bytes()).hexdigest(), 'policy differs from frozen gate set')
    require(isinstance(candidate.get('run_id'), str) and candidate['run_id'], 'candidate run_id required')
    timestamp(candidate['created_at'])
    scenes, devices = candidate.get('scenes'), candidate.get('devices')
    require(isinstance(scenes, list) and isinstance(devices, list) and len(scenes) <= 100 and len(devices) <= 100, 'candidate scene/device matrix required/bounded')
    require(all(isinstance(s, dict) and isinstance(s.get('id'), str) and s['id'] and digest(s.get('sha256'), 64) and s.get('kind') == 'physical' and s.get('authorization_verified') is True and digest(s.get('reference_sha256'), 64) for s in scenes), 'authorized physical scene/reference identity required')
    require(len({s['id'] for s in scenes}) == len(scenes) and len({s['sha256'] for s in scenes}) == len(scenes), 'duplicate scene identity')
    require(all(isinstance(d, dict) and isinstance(d.get('id'), str) and d['id'] and isinstance(d.get('identity'), str) and d['identity'] and d.get('kind') == 'physical' for d in devices), 'physical device identities required')
    require(len({d['id'] for d in devices}) == len(devices) and len({d['identity'] for d in devices}) == len(devices), 'duplicate device identity')


def check_acceptance(record):
    if record['status'] != 'PASS':
        return
    metrics, gate = record['metrics'], record['gate']
    rules = {
        'native': {'sanitizers_passed': (1, 1), 'ctest_executed': (1, 1), 'pytest_executed': (1, 1)},
        'android_build': {'compile_passed': (1, 1), 'lint_passed': (1, 1), 'jvm_tests_passed': (1, 1)},
        'instrumentation': {'full_suite_executed': (1, 1)},
        'resources': {'photos_30_completed': (1, 1), 'photos_100_completed': (1, 1), 'photos_250_completed': (1, 1), 'budget_violations': (0, 0)},
        'accessibility': {'blocking_issues': (0, 0), 'talkback_checked': (1, 1), 'font_scaling_checked': (1, 1)},
        'geometry': {'registration_fraction': (0.95, 1), 'accuracy_p95': (0, 0.01), 'completeness_fraction': (0.95, 1), 'duplicate_triangles': (0, 0), 'nonmanifold_edges': (0, 0), 'self_intersection_pairs': (0, 0)},
        'texture': {'supported_face_fraction_original': (0.95, 1), 'held_out_support_fraction': (0.95, 1), 'held_out_linear_rgb_rmse': (0, 0.05), 'seam_linear_rgb_abs_p95': (0, 0.10), 'uv_overlap_pairs': (0, 0), 'opaque_unknown_pixels': (0, 0), 'anisotropy_p95': (0, 4)},
        'interoperability': {key: (1, 1) for key in ('glb_validated', 'glb_roundtrip', 'obj_roundtrip', 'ply_roundtrip', 'stl_roundtrip')},
        'security': {key: (value, value) for key, value in {'static_executed': 1, 'secrets_executed': 1, 'dependencies_executed': 1, 'active_findings': 0}.items()},
        'dependencies': {'unresolved_licenses': (0, 0), 'unresolved_vulnerabilities': (0, 0)},
        'signing': {'production_signature_verified': (1, 1), 'debuggable': (0, 0)},
        'release_package': {'r8_exercised': (1, 1), 'aab_validated': (1, 1), 'alignment_16k_verified': (1, 1)},
        'journey': {key: (1, 1) for key in ('install', 'capture', 'import', 'calibration', 'reconstruction', 'inspection', 'editing', 'export', 'reopen')},
        'offline': {'airplane_mode': (1, 1), 'wifi_disabled': (1, 1), 'mobile_data_disabled': (1, 1), 'completed_offline': (1, 1)},
        'recovery': {'upgrade_preserved_originals': (1, 1), 'process_death_recovered': (1, 1), 'partial_not_published': (1, 1)},
        'external_beta': {'independent_testers': (1, 100000), 'unresolved_critical_defects': (0, 0)},
        'feature_wiring': {'missing_required_deliveries': (0, 0), 'unwired_required_interfaces': (0, 0), 'unresolved_ownership_conflicts': (0, 0)},
    }
    for name, (lower, upper) in rules.get(gate, {}).items():
        require(name in metrics and lower <= metrics[name] <= upper, gate + ': missing/failed measured criterion ' + name)


def qualify(candidate, records, *, now=None, evidence_root=None):
    policy = load_json(POLICY_PATH)
    now = now or datetime.now(timezone.utc)
    result = {'schema': 1, 'status': 'FAIL', 'source_sha': candidate.get('source_sha') if isinstance(candidate, dict) else None, 'artifact_sha256': candidate.get('artifact_sha256') if isinstance(candidate, dict) else None, 'gates': [], 'blockers': [], 'rejected_records': []}
    try:
        validate_candidate(candidate, policy)
        require(timestamp(candidate['created_at']) <= now, 'candidate timestamp is in the future')
    except (ValueError, KeyError, TypeError) as error:
        result['blockers'] = [str(error)]
        return result
    scenes, devices = candidate['scenes'], candidate['devices']
    if len(scenes) < policy['minimum_physical_scenes']:
        result['blockers'].append('BLOCKED_EXTERNAL: at least two authorized independent physical scene/reference bundles required')
    if len(devices) < policy['minimum_physical_devices']:
        result['blockers'].append('BLOCKED_EXTERNAL: physical ARM64 supported-device matrix required')
    admitted, seen = [], set()
    gate_definitions = {g['id']: g for g in policy['gates']}
    for index, record in enumerate(records):
        try:
            validate_record(record)
            require(record['source_sha'] == candidate['source_sha'] and record['artifact_sha256'] == candidate['artifact_sha256'], 'source/artifact mismatch')
            require(record['run_id'] == candidate['run_id'], 'stale run_id')
            recorded = timestamp(record['recorded_at'])
            require(timestamp(candidate['created_at']) <= recorded <= now and now-recorded <= timedelta(hours=policy['max_age_hours']), 'stale/future evidence timestamp')
            gate = gate_definitions.get(record['gate'])
            require(gate is not None and record['agent'] == gate['agent'], 'unknown gate or wrong evidence owner')
            data, env = record['dataset_identity'], record['environment']
            if gate.get('physical'):
                require(env['kind'] == 'physical' and env.get('emulator') is False and env.get('abi') == 'arm64-v8a', 'physical gate requires measured ARM64 handset; emulator/synthetic insufficient')
            elif gate['id'] == 'instrumentation':
                require(env['kind'] in ('emulator', 'physical') and env.get('api', 0) >= 29, 'instrumentation requires installed Android execution')
            else:
                require(env['kind'] == 'host', 'desktop gate environment mismatch')
            if record['status'] == 'PASS' and gate['id'] in ('geometry', 'texture', 'resources'):
                expected_protocol = candidate.get('protocols', {}).get(gate['id'])
                require(digest(expected_protocol, 64) and record.get('protocol_sha256') == expected_protocol, 'missing/stale frozen quality/resource protocol')
            check_acceptance(record)
            if gate.get('per_scene'):
                matches = [s for s in scenes if s['id'] == data['id']]
                require(len(matches) == 1 and data['kind'] == 'physical' and data['sha256'] == matches[0]['sha256'] and data.get('reference_sha256') == matches[0]['reference_sha256'], 'scene/reference mismatch or synthetic data')
            if gate.get('per_device'):
                matches = [d for d in devices if d['id'] == env.get('device_id')]
                require(len(matches) == 1 and matches[0]['identity'] == env['identity'], 'unsupported device identity')
            key = (record['gate'], data['id'] if gate.get('per_scene') else None, env.get('device_id') if gate.get('per_device') else None)
            require(key not in seen, 'duplicate gate/scene/device receipt')
            seen.add(key)
            if evidence_root is not None:
                blob = evidence_root / record['evidence_sha256']
                require(blob.is_file() and file_digest(blob) == record['evidence_sha256'], 'missing/tampered backing evidence blob')
            admitted.append((key, record))
        except (ValueError, TypeError, KeyError, OSError) as error:
            result['rejected_records'].append({'index': index, 'reason': str(error)})
    for gate in policy['gates']:
        expected = [(gate['id'], s['id'] if s else None, d['id'] if d else None) for s in (scenes if gate.get('per_scene') else [None]) for d in (devices if gate.get('per_device') else [None])]
        if not expected:
            result['gates'].append({'gate': gate['id'], 'status': 'BLOCKED_EXTERNAL', 'blockers': ['required physical scene/device matrix unavailable']})
        for key in expected:
            receipt = next((r for k, r in admitted if k == key), None)
            entry = {'gate': key[0], 'scene': key[1], 'device': key[2], 'status': receipt['status'] if receipt else 'NOT_RUN', 'blockers': receipt['blockers'] if receipt else ['no admissible exact-candidate receipt']}
            if receipt:
                entry['metrics'] = receipt['metrics']
                entry['evidence_sha256'] = receipt['evidence_sha256']
            result['gates'].append(entry)
    statuses = {g['status'] for g in result['gates']}
    if result['rejected_records'] or 'FAIL' in statuses:
        result['status'] = 'FAIL'
    elif result['blockers'] or statuses != {'PASS'}:
        result['status'] = 'BLOCKED_EXTERNAL' if result['blockers'] or 'BLOCKED_EXTERNAL' in statuses else 'NOT_RUN'
    else:
        result['status'] = 'PASS'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--records', type=Path, required=True)
    parser.add_argument('--evidence-root', type=Path, required=True)
    parser.add_argument('--candidate-apk', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        candidate = load_json(args.candidate)
        actual_source = subprocess.check_output(['git', '-C', str(args.source_root), 'rev-parse', 'HEAD'], text=True).strip()
        require(actual_source == candidate['source_sha'], 'source checkout differs from candidate')
        require(subprocess.run(['git', '-C', str(args.source_root), 'diff', '--quiet', 'HEAD'], check=False).returncode == 0, 'candidate checkout has tracked modifications')
        require(not subprocess.check_output(['git', '-C', str(args.source_root), 'status', '--porcelain', '--untracked-files=normal'], text=True).strip(), 'candidate checkout has uncommitted/untracked source')
        require(file_digest(args.candidate_apk) == candidate['artifact_sha256'], 'candidate APK bytes differ from frozen artifact')
        paths = sorted(args.records.glob('*.json'))
        require(len(paths) <= 10000, 'excessive receipt count')
        records = [load_json(p) for p in paths]
        report = qualify(candidate, records, evidence_root=args.evidence_root)
    except (ValueError, KeyError, TypeError, OSError, RecursionError, subprocess.SubprocessError) as error:
        report = {'schema': 1, 'status': 'FAIL', 'blockers': [str(error)]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'status': report['status'], 'blockers': report['blockers'], 'rejected_records': report.get('rejected_records', [])}))
    return 0 if report['status'] == 'PASS' else 1 if report['status'] == 'FAIL' else 2


if __name__ == '__main__':
    raise SystemExit(main())
