#!/usr/bin/env python3
"""Canonical SHA-256 manifest for the complete staged or committed Git tree."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys

MANIFEST = "SOURCE_MANIFEST.sha256"


class ManifestError(ValueError):
    pass


def git(root: Path, *args: str) -> bytes:
    try:
        return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exc:
        raise ManifestError(exc.stderr.decode("utf-8", "replace").strip()) from exc


def repository(path: Path) -> Path:
    return Path(git(path, "rev-parse", "--show-toplevel").decode().strip())


def safe_path(raw: bytes) -> str:
    try:
        path = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ManifestError("Tracked paths must be UTF-8") from exc
    if (not path or path.startswith("/") or "\\" in path
            or any(ord(c) < 32 or ord(c) == 127 for c in path)
            or any(part in ("", ".", "..", ".git") for part in path.split("/"))
            or str(PurePosixPath(path)) != path):
        raise ManifestError(f"Unsafe/noncanonical tracked path: {path!r}")
    return path


def entries(root: Path, source: str = "index") -> dict[str, tuple[str, str]]:
    """Return exact regular-file set; reject unresolved conflicts and special entries."""
    result = {}
    if source in ("index", "worktree"):
        # Intent-to-add appears in ls-files with the empty blob but is omitted
        # from a commit. Reject it rather than manifesting a tree Git cannot commit.
        visible = git(root, "diff", "--cached", "--no-renames", "--name-only", "-z", "--ita-visible-in-index")
        staged = git(root, "diff", "--cached", "--no-renames", "--name-only", "-z", "--ita-invisible-in-index")
        if visible != staged:
            raise ManifestError("Fully stage or remove intent-to-add entries before manifest generation/verification")
        records = git(root, "ls-files", "--stage", "-z")
    else:
        revision = git(root, "rev-parse", "--verify", source + "^{commit}").decode().strip()
        records = git(root, "ls-tree", "-r", "-z", revision)
    for row in records.split(b"\0"):
        if not row:
            continue
        metadata, raw = row.split(b"\t", 1)
        mode, oid, stage = metadata.split() if source in ("index", "worktree") else (metadata.split()[0], metadata.split()[2], b"0")
        path = safe_path(raw)
        if stage != b"0":
            raise ManifestError("Resolve all semantic merge conflicts before manifest regeneration")
        if mode not in (b"100644", b"100755"):
            raise ManifestError(f"Unsupported tracked entry {path}: {mode.decode()}")
        if path in result:
            raise ManifestError(f"Duplicate tracked path: {path}")
        result[path] = (mode.decode(), oid.decode())
    return result


def content(root: Path, path: str, oid: str, source: str) -> bytes:
    if source != "worktree":
        return git(root, "cat-file", "blob", oid)
    candidate = root / path
    try:
        # Reject symlinks in every component, including directory symlinks.
        for part in (candidate, *candidate.parents):
            if part == root:
                break
            if part.is_symlink():
                raise ManifestError(f"Symlink in tracked worktree path: {path}")
        if not stat.S_ISREG(candidate.stat().st_mode):
            raise ManifestError(f"Not a regular file: {path}")
        return candidate.read_bytes()
    except OSError as exc:
        raise ManifestError(f"Cannot read tracked file {path}: {exc}") from exc


def render(root: Path, source: str = "index") -> bytes:
    files = entries(root, source)
    rows = []
    for path, (_, oid) in sorted(files.items()):
        if path != MANIFEST:
            digest = hashlib.sha256(content(root, path, oid, source)).hexdigest()
            rows.append(f"{digest}  {path}\n")
    return "".join(rows).encode("utf-8")


def parse(data: bytes) -> dict[str, str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ManifestError("Manifest must be UTF-8") from exc
    result = {}
    for row in text.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", row)
        if not match:
            raise ManifestError(f"Malformed manifest row: {row!r}")
        digest, path = match.groups()
        safe_path(path.encode())
        if path == MANIFEST or path in result:
            raise ManifestError(f"Duplicate/self manifest entry: {path}")
        result[path] = digest
    canonical = "".join(f"{digest}  {path}\n" for path, digest in sorted(result.items())).encode()
    if data != canonical:
        raise ManifestError("Manifest must use sorted canonical rows and LF termination")
    return result


def verify(root: Path, source: str = "worktree") -> dict[str, int | str]:
    files = entries(root, source)
    if MANIFEST not in files:
        raise ManifestError("Manifest is missing from the tracked tree")
    actual = parse(content(root, MANIFEST, files[MANIFEST][1], source))
    expected = parse(render(root, source))
    if actual.keys() != expected.keys():
        raise ManifestError(f"Manifest tracked-set mismatch; missing={sorted(expected.keys() - actual.keys())}; stale={sorted(actual.keys() - expected.keys())}")
    bad = [path for path in expected if actual[path] != expected[path]]
    if bad:
        raise ManifestError(f"Manifest hash mismatch: {bad}")
    return {"source": source, "files": len(expected), "status": "PASS"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "verify"))
    parser.add_argument("--source", help="index, worktree (verify only), or exact Git revision (verify only)")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        root = repository(args.repo)
        source = args.source or ("index" if args.command == "generate" else "worktree")
        if args.command == "generate":
            if source != "index":
                raise ManifestError("Generation is allowed only from the staged index")
            (root / MANIFEST).write_bytes(render(root, source))
            print("Generated manifest from staged Git blobs; stage SOURCE_MANIFEST.sha256 next")
        else:
            import json
            print(json.dumps(verify(root, source), sort_keys=True))
    except (ManifestError, OSError) as exc:
        print(f"source manifest: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
