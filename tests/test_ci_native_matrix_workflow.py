"""Static wiring contracts; these do not claim Actions or sanitizer execution."""
from fnmatch import fnmatchcase
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    # BaseLoader preserves the YAML key 'on' rather than YAML 1.1 boolean True.
    return yaml.load((ROOT / '.github/workflows' / name).read_text(), Loader=yaml.BaseLoader)


def commands(job):
    return '\n'.join(step.get('run', '') for step in job['steps'])


def test_verify_runs_each_profile_once_and_keeps_native_aggregate_identity():
    workflow = load('verify.yml')
    jobs = workflow['jobs']
    shards = jobs['native-shards']
    assert shards['strategy'] == {'fail-fast': 'false', 'matrix': {'config': [
        'gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize']}}
    assert shards['timeout-minutes'] == '50'
    runner = [step['run'] for step in shards['steps'] if 'run.py --configs' in step.get('run', '')]
    assert runner == ['python tools/qa/native_regression/run.py --configs "${{ matrix.config }}" --output "$RUNNER_TEMP/rf-ci/native/runner"']
    assert 'source_manifest.py verify --source HEAD' in commands(shards)
    assert 'native_matrix.py begin --repository "$GITHUB_REPOSITORY" --profile "${{ matrix.config }}"' in commands(shards)
    assert '--run-id "$GITHUB_RUN_ID:$GITHUB_RUN_ATTEMPT"' in commands(shards)
    assert 'native_matrix.py seal --start' in commands(shards)
    assert 'install_qa.py' in commands(shards)
    assert '--require-portable-blender' in commands(shards)
    aggregate = jobs['native']
    assert aggregate['timeout-minutes'] == '10'
    assert aggregate['needs'] == 'native-shards'
    assert aggregate['if'] == '${{ !cancelled() }}'
    assert aggregate['steps'][0]['env']['SHARD_RESULT'] == '${{ needs.native-shards.result }}'
    assert '== "success"' in aggregate['steps'][0]['run']
    assert 'Native shard failed, skipped or cancelled' in aggregate['steps'][0]['run']
    assert 'native_matrix.py aggregate --repository "$GITHUB_REPOSITORY" --directory' in commands(aggregate)
    assert 'native_regression/run.py' not in commands(aggregate)
    assert 'install_qa.py' not in commands(aggregate)
    assert jobs['candidate-evidence']['needs'] == ['native', 'android', 'device', 'benchmark-build']
    for job in (shards, aggregate, jobs['candidate-evidence']):
        assert 'python -m pip install --require-hashes --only-binary=:all: -r tools/requirements.lock' in commands(job)
        assert 'continue-on-error' not in job
        assert not any('continue-on-error' in step for step in job['steps'])


def test_native_shards_retain_separate_current_attempt_artifacts_and_diagnostics():
    jobs = load('verify.yml')['jobs']
    steps = jobs['native-shards']['steps']
    upload = next(step for step in steps if step.get('with', {}).get('name', '').startswith('rf-native-shard-'))
    assert upload['with']['name'] == 'rf-native-shard-${{ matrix.config }}-${{ github.sha }}-${{ github.run_attempt }}'
    assert upload['with']['if-no-files-found'] == 'error'
    assert upload['if'] == 'always()'
    failures = next(step for step in steps if step.get('with', {}).get('name', '').startswith('rf-native-failure-logs-'))
    assert failures['if'] == 'failure() || cancelled()'
    assert '${{ matrix.config }}' in failures['with']['name']
    assert '/runner/evidence.json' in failures['with']['path']
    for suffix in ('/*/numerical.json', '/*/benchmark_*.json', '/*/pytest.xml'):
        assert '/runner' + suffix in failures['with']['path']
    assert '/runner/' not in failures['with']['path'].splitlines()
    download = next(step for step in jobs['native']['steps'] if step.get('uses', '').startswith('actions/download-artifact@'))
    assert download['with'] == {
        'pattern': 'rf-native-shard-*-${{ github.sha }}-${{ github.run_attempt }}',
        'path': '${{ runner.temp }}/native-shards', 'merge-multiple': 'false'}
    assert 'github-token' not in download['with'] and 'run-id' not in download['with']
    final = next(step for step in jobs['native']['steps'] if step.get('uses', '').startswith('actions/upload-artifact@'))
    assert final['with']['name'] == 'rf-evidence-native-${{ github.sha }}-${{ github.run_attempt }}'
    assert 'if' not in final  # Only successfully verified aggregation publishes acceptance.


def test_native_event_partition_preserves_other_branch_coverage_without_overlap():
    verify = load('verify.yml')
    standalone = load('native-sanitizers.yml')
    included = verify['on']['push']['branches']
    excluded = standalone['on']['push']['branches-ignore']
    assert included == excluded == ['main', 'agents/**', 'integration/**']
    assert 'pull_request' in verify['on'] and 'pull_request' not in standalone['on']
    assert 'workflow_dispatch' in verify['on'] and 'workflow_dispatch' in standalone['on']
    for branch in ['main', 'agents/one', 'agents/nested/worker', 'integration/test',
                   'feature/scan', 'release/v1', 'maintenance', 'main-other']:
        in_verify = any(fnmatchcase(branch, pattern) for pattern in included)
        in_standalone = not any(fnmatchcase(branch, pattern) for pattern in excluded)
        assert int(in_verify) + int(in_standalone) == 1
    for workflow in (verify, standalone):
        assert workflow['permissions'] == {'contents': 'read'}
        assert all(job['runs-on'] == 'ubuntu-24.04' for job in workflow['jobs'].values())
