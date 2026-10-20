"""Behavioral regressions for staged-tree provenance and exact-commit archives."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tarfile

import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("source_manifest", ROOT / "tools/source_manifest.py")
manifest = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manifest)
ARCHIVE = ROOT / "tools/ci/source_snapshot/archive.py"


def git(repo, *args, check=True):
    return subprocess.run(["git", "-C", str(repo), *args], check=check, capture_output=True).stdout


@pytest.fixture
def repo(tmp_path):
    path = tmp_path / "repo"
    path.mkdir()
    git(path, "init", "-q")
    git(path, "config", "user.name", "Manifest Test")
    git(path, "config", "user.email", "manifest@example.invalid")
    (path / "a.txt").write_text("original\n")
    (path / ".gitignore").write_text("ignored*\n")
    (path / "SOURCE_MANIFEST.sha256").write_text("")
    git(path, "add", ".")
    regenerate(path)
    git(path, "commit", "-qm", "baseline")
    return path


def regenerate(repo):
    (repo / manifest.MANIFEST).write_bytes(manifest.render(repo, "index"))
    git(repo, "add", manifest.MANIFEST)


def run_archive(repo, command, *args):
    return subprocess.run([sys.executable, str(ARCHIVE), command, "--repo", str(repo), "--revision", "HEAD", *map(str, args)], capture_output=True, text=True)


def test_staged_content_add_delete_rename_and_unstaged_isolation(repo):
    (repo / "a.txt").write_text("staged\n")
    (repo / "new name.txt").write_text("addition\n")
    git(repo, "add", ".")
    git(repo, "mv", "a.txt", "renamed.txt")
    git(repo, "rm", ".gitignore")
    (repo / "renamed.txt").write_text("unstaged mutation\n")
    regenerate(repo)
    assert manifest.verify(repo, "index")["files"] == 2
    assert manifest.parse(manifest.render(repo))["renamed.txt"] == hashlib.sha256(b"staged\n").hexdigest()
    with pytest.raises(manifest.ManifestError, match="hash mismatch"):
        manifest.verify(repo, "worktree")


def test_exact_revision_ignores_dirty_worktree(repo):
    assert manifest.verify(repo, "HEAD")["status"] == "PASS"
    (repo / "a.txt").write_text("dirty")
    assert manifest.verify(repo, "HEAD")["status"] == "PASS"
    with pytest.raises(manifest.ManifestError):
        manifest.verify(repo, "worktree")


@pytest.mark.parametrize("mutation", ["omitted", "stale", "duplicate", "hash", "self", "format", "path", "newline"])
def test_manifest_corruption_is_rejected(repo, mutation):
    target = repo / manifest.MANIFEST
    rows = target.read_text().splitlines(keepends=True)
    if mutation == "omitted":
        rows.pop()
    elif mutation == "stale":
        rows.append("0" * 64 + "  z-missing.txt\n")
    elif mutation == "duplicate":
        rows.append(rows[-1])
    elif mutation == "hash":
        rows[-1] = "0" * 64 + rows[-1][64:]
    elif mutation == "self":
        rows.append("0" * 64 + "  SOURCE_MANIFEST.sha256\n")
    elif mutation == "format":
        rows[-1] = rows[-1].replace("  ", " ")
    elif mutation == "path":
        rows[-1] = "0" * 64 + "  ../escape\n"
    elif mutation == "newline":
        rows[-1] = rows[-1].rstrip("\n")
    target.write_text("".join(rows))
    with pytest.raises(manifest.ManifestError):
        manifest.verify(repo, "worktree")


def test_new_tracked_file_cannot_hide_outside_manifest(repo):
    (repo / "new.txt").write_text("must be listed")
    git(repo, "add", "new.txt")
    with pytest.raises(manifest.ManifestError, match="tracked-set mismatch"):
        manifest.verify(repo, "index")


def test_unresolved_semantic_conflict_cannot_be_generated(repo):
    branch = git(repo, "branch", "--show-current").decode().strip()
    git(repo, "checkout", "-qb", "other")
    (repo / "a.txt").write_text("other\n")
    git(repo, "commit", "-qam", "other")
    git(repo, "checkout", branch)
    (repo / "a.txt").write_text("current\n")
    git(repo, "commit", "-qam", "current")
    git(repo, "merge", "other", check=False)
    with pytest.raises(manifest.ManifestError, match="semantic merge conflicts"):
        manifest.render(repo)


@pytest.mark.parametrize("kind", ["symlink", "newline", "backslash"])
def test_unsupported_git_entries_fail_closed(repo, kind):
    if kind == "symlink":
        (repo / "link").symlink_to("a.txt")
    else:
        (repo / ("bad\nname" if kind == "newline" else "bad\\name")).write_text("unsafe")
    git(repo, "add", ".")
    with pytest.raises(manifest.ManifestError):
        manifest.render(repo)


def test_worktree_symlink_cannot_impersonate_tracked_regular_file(repo):
    (repo / "a.txt").unlink()
    (repo / "a.txt").symlink_to(repo / ".gitignore")
    with pytest.raises(manifest.ManifestError, match="Symlink"):
        manifest.verify(repo, "worktree")


def test_archive_exact_git_tree_reproducible_and_normalized(repo, tmp_path):
    executable = repo / "run.sh"
    executable.write_text("#!/bin/sh\nexit 0\n")
    executable.chmod(0o755)
    git(repo, "add", ".")
    regenerate(repo)
    git(repo, "commit", "-qm", "executable")
    (repo / "ignored-secret.txt").write_text("not tracked")
    (repo / "untracked.txt").write_text("not tracked either")
    (repo / "a.txt").write_text("dirty checkout")
    archives = []
    for index in range(2):
        archive = tmp_path / f"source{index}.tar.gz"
        record = tmp_path / f"source{index}.json"
        result = run_archive(repo, "create", "--output", archive, "--record", record)
        assert result.returncode == 0, result.stderr
        assert run_archive(repo, "verify", "--archive", archive, "--record", record).returncode == 0
        archives.append(archive)
    assert archives[0].read_bytes() == archives[1].read_bytes()
    with tarfile.open(archives[0]) as archive:
        assert set(archive.getnames()) == set(manifest.entries(repo, "HEAD"))
        assert archive.extractfile("a.txt").read() == b"original\n"
        for info in archive.getmembers():
            assert info.isfile()
            assert info.uid == info.gid == info.mtime == 0
            assert info.uname == info.gname == ""
            assert info.mode == (0o755 if info.name == "run.sh" else 0o644)


@pytest.mark.parametrize("tamper", ["archive", "record", "revision"])
def test_archive_tampering_and_wrong_revision_fail(repo, tmp_path, tamper):
    archive, record = tmp_path / "source.tar.gz", tmp_path / "source.json"
    assert run_archive(repo, "create", "--output", archive, "--record", record).returncode == 0
    if tamper == "archive":
        archive.write_bytes(archive.read_bytes() + b"untracked payload")
    elif tamper == "record":
        value = json.loads(record.read_text())
        value["source_commit"] = "0" * 40
        record.write_text(json.dumps(value))
    else:
        (repo / "a.txt").write_text("new revision")
        git(repo, "add", ".")
        regenerate(repo)
        git(repo, "commit", "-qm", "new revision")
    assert run_archive(repo, "verify", "--archive", archive, "--record", record).returncode != 0


def test_archive_refuses_commit_with_stale_manifest(repo, tmp_path):
    (repo / "new.txt").write_text("not listed")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "invalid manifest")
    assert run_archive(repo, "create", "--output", tmp_path / "bad.tar.gz", "--record", tmp_path / "bad.json").returncode != 0


def test_intent_to_add_cannot_manifest_an_uncommittable_index(repo):
    (repo / "intent.txt").write_text("not staged content")
    git(repo, "add", "--intent-to-add", "intent.txt")
    with pytest.raises(manifest.ManifestError, match="intent-to-add"):
        manifest.render(repo, "index")
    git(repo, "add", "intent.txt")
    regenerate(repo)
    assert manifest.verify(repo, "index")["status"] == "PASS"
