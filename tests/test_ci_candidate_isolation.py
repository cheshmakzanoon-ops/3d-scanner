"""Synthetic Git fixture reuse must not reuse mutable source or evidence state."""
import shutil
import subprocess

import pytest
import test_ci_evidence_validation as fixtures


def snapshot(root):
    return {str(path.relative_to(root)): (path.read_bytes(), path.stat().st_mode,
                                         path.stat().st_mtime_ns)
            for path in root.rglob('*') if path.is_file()}


@pytest.mark.parametrize('mutation', ['readme', 'manifest', 'commit', 'delete-source',
                                     'delete-object', 'delete-repository'])
def test_candidate_copies_isolate_mutations(ci_repository_template, tmp_path, mutation):
    template, original_commit = ci_repository_template
    original = snapshot(template)
    first = fixtures.copy_candidate(ci_repository_template, tmp_path / 'first')
    second = fixtures.copy_candidate(ci_repository_template, tmp_path / 'second')
    for relative in original:
        paths = [root / relative for root in (template, first[0], second[0])]
        assert len({(path.stat().st_dev, path.stat().st_ino) for path in paths}) == 3
        assert all(not path.is_symlink() for path in paths)
    for repo in (first[0], second[0]):
        assert (repo / '.git').is_dir()
        assert not (repo / '.git/objects/info/alternates').exists()
        assert fixtures.ci.source(repo, original_commit) == original_commit
    first_marker = fixtures.start(first, 'native')
    if mutation in ('readme', 'commit'):
        (first[0] / 'README').write_text('changed synthetic source')
        if mutation == 'commit':
            subprocess.run(['git', '-C', str(first[0]), 'commit', '-qam', 'isolated commit'], check=True)
    elif mutation == 'manifest':
        (first[0] / fixtures.ci.SUITE_PATH).write_text('{}')
    elif mutation == 'delete-source':
        (first[0] / 'README').unlink()
    elif mutation == 'delete-object':
        (first[0] / '.git/objects' / original_commit[:2] / original_commit[2:]).unlink()
    else:
        shutil.rmtree(first[0])
    with pytest.raises((fixtures.ci.EvidenceError, subprocess.CalledProcessError)):
        fixtures.ci.source(first[0], original_commit)
    assert fixtures.ci.source(second[0], original_commit) == original_commit
    assert snapshot(template) == original
    # A later test copy is pristine even after committed changes or deletion.
    third = fixtures.copy_candidate(ci_repository_template, tmp_path / 'third')
    assert snapshot(third[0]) == original
    assert fixtures.ci.source(third[0], original_commit) == original_commit
    second_marker = fixtures.start(second, 'native')
    before, after = map(fixtures.ci.load, (first_marker, second_marker))
    assert before['root'] != after['root']
    assert before['started_ns'] < after['started_ns']
    assert not third[2].exists() and not third[3].exists()
    assert snapshot(template) == original
