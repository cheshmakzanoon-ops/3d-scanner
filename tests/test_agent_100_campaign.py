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
