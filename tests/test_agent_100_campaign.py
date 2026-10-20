import importlib.util
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('campaign', ROOT / 'tools/agents/campaign.py')
campaign = importlib.util.module_from_spec(spec)
spec.loader.exec_module(campaign)


def test_all_assignments_and_exclusive_ownership_are_parseable():
    rows = campaign.roster((ROOT / campaign.PLAN).read_text())
    assert len(rows) == 100
    assert rows[99]['branch'] == 'agents/100-integration-lead'
    assert campaign.owned(rows[88], 'tools/device/offline_privacy/audit.py')
    assert not campaign.owned(rows[88], 'app/src/main/AndroidManifest.xml')
    assert campaign.owned(rows[88], 'docs/agents/requests/089-to-008.md')
    assert not campaign.owned(rows[88], 'docs/agents/requests/088-to-008.md')


def test_missing_duplicate_and_unsafe_roster_fail():
    text = (ROOT / campaign.PLAN).read_text()
    for changed in [text.replace('## Agent 050\n', '## Agent 049\n'),
                    text.replace('`tools/agents/`', '`../outside/`'),
                    text.replace('**Role:** Integration lead and release acceptance coordinator', 'missing role')]:
        with pytest.raises(ValueError):
            campaign.roster(changed)


@pytest.mark.parametrize('claim', ['tools/agents/', 'tools/', 'tools/agents/campaign.py'])
def test_reserved_and_existing_prefix_collisions_fail(claim):
    text = (ROOT / campaign.PLAN).read_text().replace('`tools/device/offline_privacy/`', '`' + claim + '`')
    with pytest.raises(ValueError, match='Overlapping source ownership'):
        campaign.roster(text)


def test_rename_cannot_hide_unowned_source(tmp_path):
    def run(*args):
        return subprocess.check_output(['git', '-C', str(tmp_path), *args], text=True).strip()
    run('init', '-q'); run('config', 'user.email', 'fixture@example.invalid'); run('config', 'user.name', 'Fixture')
    (tmp_path / 'owned.txt').write_text('keep')
    (tmp_path / 'other.txt').write_text('cannot change')
    run('add', '.'); run('commit', '-qm', 'base'); base = run('rev-parse', 'HEAD')
    run('mv', 'other.txt', 'renamed.txt'); run('commit', '-qm', 'bad rename')
    rows = [{'agent': '089', 'receipt': 'docs/agents/status/089.md', 'existing': ['owned.txt'], 'reserved': []}]
    assert campaign.ownership(tmp_path, rows, '089', base, 'HEAD') == ['other.txt', 'renamed.txt']


def test_absent_branches_never_close_campaign(tmp_path):
    subprocess.run(['git', 'init', '-q', str(tmp_path)], check=True)
    rows = campaign.roster((ROOT / campaign.PLAN).read_text())
    result = campaign.index(tmp_path, rows, 'a' * 40)
    assert not result['campaign_complete']
    assert len(result['deliveries']) == 100
    assert all(r['state'] == 'NOT_RUN' and r['source_sha'] is None for r in result['deliveries'])


def test_receipt_mapping_and_allowed_states_cannot_infer_success():
    assert campaign.receipt_state('{"state": "BLOCKED_EXTERNAL", "agent": "083"}') == 'BLOCKED_EXTERNAL'
    assert campaign.receipt_state('agent: 094\nstate: DONE\n') == 'DONE'
    for text in ['State: IMPLEMENTED', 'All tests passed', '{"status":"PASS"}',
                 'state: IMPLEMENTED', 'narrative state: DONE', 'state: DONE_WITH_EXCEPTIONS']:
        assert campaign.receipt_state(text) == 'INVALID_RECEIPT'


def test_done_needs_actual_source_bound_acceptance_and_no_contradictory_result():
    import json
    minimal = {'state': 'DONE'}
    assert campaign.receipt_checks(json.dumps(minimal))
    record = {'state': 'DONE', 'implementation_commits': ['a' * 40],
              'remaining_work': [], 'external_blockers': [],
              'acceptance_results': [{'criterion': 'real gate', 'result': 'PASS',
                                      'command': 'existing-test-runner', 'source_sha': 'a' * 40}]}
    assert campaign.receipt_checks(json.dumps(record)) == []
    for status in ['FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL']:
        record['acceptance_results'][0]['result'] = status
        assert campaign.receipt_checks(json.dumps(record))
    record['acceptance_results'][0]['result'] = 'PASS'
    record['acceptance_results'][0].pop('source_sha')
    assert campaign.receipt_checks(json.dumps(record))


@pytest.mark.parametrize('command', [True, 1, [], {}, '', '   '])
@pytest.mark.parametrize('field', ['command', 'criterion'])
def test_pass_requires_real_command_and_criterion_strings(command, field):
    import json
    record = {'state': 'DONE', 'implementation_commits': ['a' * 40],
              'remaining_work': [], 'external_blockers': [],
              'acceptance_results': [{'criterion': 'actual run', 'result': 'PASS',
                                      'command': 'actual-test-command', 'source_sha': 'a' * 40}]}
    record['acceptance_results'][0][field] = command
    assert campaign.receipt_checks(json.dumps(record))


def test_frontmatter_first_field_order_and_single_json_envelope_are_supported():
    import json
    front = '''---
agent: "098"
state: DONE
implementation_commits: ["''' + 'a' * 40 + '''"]
acceptance_results:
  - source_sha: "''' + 'a' * 40 + '''"
    criterion: "runbook current"
    result: PASS
    command: "python checker.py"
remaining_work: []
external_blockers: []
---
# Narrative follows
'''
    assert campaign.receipt_state(front) == 'DONE'
    assert campaign.receipt_checks(front) == []
    mapping = {'state': 'READY_FOR_INTEGRATION', 'implementation_commits': ['b' * 40],
               'command': 'python numerical-runner', 'tested_source_sha': 'b' * 40,
               'acceptance_results': [{'criterion': 'native', 'result': 'PASS'},
                                      {'criterion': 'Python lifetime', 'result': 'FAIL'}]}
    fenced = '# Receipt\n\n```json\n' + json.dumps(mapping) + '\n```\n'
    assert campaign.receipt_state(fenced) == 'READY_FOR_INTEGRATION'
    assert campaign.receipt_checks(fenced) == []


@pytest.mark.parametrize('indent', ['', '  '])
def test_compact_yaml_completion_requires_the_same_evidence(indent):
    text = 'state: DONE\nimplementation_commits: [' + 'b' * 40 + ''']
acceptance_results:
  - criterion: actual run
    result: PASS
    command: test-command
    source_sha: ''' + 'b' * 40 + '''
remaining_work: []
external_blockers: []
next_action: integrate
'''
    text = text.replace('  - criterion:', indent + '- criterion:').replace(
        '    result:', indent + '  result:').replace(
        '    command:', indent + '  command:').replace(
        '    source_sha:', indent + '  source_sha:')
    assert campaign.receipt_checks(text) == []
    assert campaign.receipt_checks(text.replace('result: PASS', 'result: FAIL'))
    assert campaign.receipt_checks(text.replace('    source_sha:', '    absent:').replace(
        '  source_sha:', '  absent:'))


def test_integration_ledger_cannot_launder_an_unowned_ancestor_change(tmp_path):
    import json
    def run(*args):
        return subprocess.check_output(['git', '-C', str(tmp_path), *args], text=True).strip()
    run('init', '-q'); run('config', 'user.email', 'fixture@example.invalid'); run('config', 'user.name', 'Fixture')
    (tmp_path / 'owned.txt').write_text('original')
    (tmp_path / 'foreign.txt').write_text('preserve')
    run('add', '.'); run('commit', '-qm', 'base'); base = run('rev-parse', 'HEAD')
    (tmp_path / 'owned.txt').write_text('delivery')
    (tmp_path / 'foreign.txt').write_text('unowned mutation')
    run('add', '.'); run('commit', '-qm', 'mixed delivery'); mixed = run('rev-parse', 'HEAD')
    folder = tmp_path / 'docs/agents/integration'; folder.mkdir(parents=True)
    (folder / 'deliveries.json').write_text(json.dumps({'deliveries': [{'agent': '089', 'source_sha': mixed}]}))
    run('add', '.'); run('commit', '-qm', 'integration index')
    rows = [{'agent': '089', 'receipt': 'docs/agents/status/089.md', 'existing': ['owned.txt'], 'reserved': []},
            {'agent': '100', 'receipt': 'docs/agents/status/100.md', 'existing': [], 'reserved': ['docs/agents/integration/']}]
    assert campaign.ownership(tmp_path, rows, '100', base, 'HEAD') == ['foreign.txt', 'owned.txt']


def test_frozen_base_rejects_missing_or_invalid_registry(tmp_path):
    import json
    folder = tmp_path / 'docs/agents/integration'; folder.mkdir(parents=True)
    path = folder / 'campaign-base.json'
    path.write_text(json.dumps({'campaign_base': 'c' * 40}))
    assert campaign.frozen_base(tmp_path) == 'c' * 40
    path.write_text(json.dumps({'campaign_base': 'HEAD'}))
    with pytest.raises(ValueError):
        campaign.frozen_base(tmp_path)


@pytest.mark.parametrize('mutation', ['edit', 'rename_to_receipt'])
def test_done_receipt_only_followup_allowed_but_changed_runtime_is_stale(tmp_path, mutation):
    import json
    def run(*args):
        return subprocess.check_output(['git', '-C', str(tmp_path), *args], text=True).strip()
    run('init', '-q'); run('config', 'user.email', 'fixture@example.invalid'); run('config', 'user.name', 'Fixture')
    (tmp_path / 'runtime.py').write_text('print("tested")')
    run('add', '.'); run('commit', '-qm', 'tested source'); tested = run('rev-parse', 'HEAD')
    record = {'state': 'DONE', 'implementation_commits': [tested], 'remaining_work': [], 'external_blockers': [],
              'acceptance_results': [{'criterion': 'actual gate', 'result': 'PASS', 'command': 'python runtime.py', 'source_sha': tested}]}
    text = json.dumps(record)
    folder = tmp_path / 'docs/agents/status'; folder.mkdir(parents=True)
    (folder / '089.md').write_text(text)
    run('add', '.'); run('commit', '-qm', 'receipt only')
    assert campaign.receipt_freshness(tmp_path, text, '089', 'HEAD') == []
    if mutation == 'edit':
        (tmp_path / 'runtime.py').write_text('raise RuntimeError("untested")')
    else:
        destination = tmp_path / 'docs/agents/requests/089-to-095.md'
        destination.parent.mkdir(parents=True)
        run('mv', 'runtime.py', str(destination))
    run('add', '.'); run('commit', '-qm', 'untested change')
    assert campaign.receipt_freshness(tmp_path, text, '089', 'HEAD') == [
        'DONE has untested source/input changes after evidence: runtime.py']


@pytest.mark.parametrize('mutation', ['unchanged', 'unstaged', 'staged', 'untracked', 'preexisting'])
def test_real_merge_helper_rejects_postcheck_source_change(tmp_path, mutation):
    import json
    import shutil
    import sys
    def run(*args):
        return subprocess.check_output(['git', '-C', str(tmp_path), *args], text=True).strip()
    run('init', '-q'); run('config', 'user.email', 'fixture@example.invalid'); run('config', 'user.name', 'Fixture')
    for name in ['.gitignore', 'tools/agents/campaign.py', 'tools/agents/integrate.py', 'tools/source_manifest.py', campaign.PLAN]:
        target = tmp_path / name; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, target)
    def commit(message):
        run('add', '.')
        subprocess.run([sys.executable, str(tmp_path / 'tools/source_manifest.py'), 'generate', '--source', 'index'],
                       cwd=tmp_path, check=True, capture_output=True)
        run('add', 'SOURCE_MANIFEST.sha256'); run('commit', '-qm', message)
        return run('rev-parse', 'HEAD')
    base = commit('campaign base')
    run('switch', '-qc', 'agents/089-runtime-offline-privacy')
    runtime = tmp_path / 'tools/device/offline_privacy/candidate.py'
    runtime.parent.mkdir(parents=True); runtime.write_text('value = 42\n')
    delivery = commit('owned delivery')
    run('switch', '-qc', 'agents/100-integration-lead', base)
    registry = tmp_path / 'docs/agents/integration/campaign-base.json'; registry.parent.mkdir(parents=True)
    registry.write_text(json.dumps({'campaign_base': base})); original = commit('freeze base')
    command = "from pathlib import Path; Path('tools/device/offline_privacy/candidate.py').write_text('changed after check')"
    if mutation == 'staged':
        command += "; import subprocess; subprocess.run(['git','add','.'],check=True); subprocess.run(['python3','tools/source_manifest.py','generate','--source','index'],check=True); subprocess.run(['git','add','SOURCE_MANIFEST.sha256'],check=True)"
    elif mutation == 'untracked':
        command = "from pathlib import Path; import runpy; Path('extra.py').write_text('value = 42\\n'); assert runpy.run_path('extra.py')['value'] == 42"
    elif mutation == 'preexisting':
        (tmp_path / 'extra.py').write_text('value = 42\n')
        command = "import runpy; assert runpy.run_path('extra.py')['value'] == 42"
    elif mutation == 'unchanged':
        command = "import runpy; assert runpy.run_path('tools/device/offline_privacy/candidate.py')['value'] == 42"
    import shlex
    test = subprocess.run([sys.executable, str(tmp_path / 'tools/agents/integrate.py'), '--agent', '089',
                           '--source-sha', delivery, '--base', base, '--check',
                           sys.executable + ' -c ' + shlex.quote(command)], cwd=tmp_path, text=True, capture_output=True)
    if mutation == 'unchanged':
        assert test.returncode == 0, test.stdout + test.stderr
        assert run('rev-parse', 'HEAD') != original
        return
    assert test.returncode != 0, test.stdout + test.stderr
    assert run('rev-parse', 'HEAD') == original
    assert ('untracked inputs' if mutation == 'preexisting' else 'Verification') in test.stderr
