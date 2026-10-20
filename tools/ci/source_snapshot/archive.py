#!/usr/bin/env python3
"""Create/verify canonical exact-commit source tar.gz archives, without a checkout."""
from __future__ import annotations
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from source_manifest import MANIFEST, ManifestError, content, entries, git, repository, verify

POLICY = "git-regular-files-v1;sorted-paths;pax;uid=gid=mtime=0;gzip-mtime=0;level=9"


def identity(root: Path, revision: str) -> tuple[str, str]:
    commit = git(root, "rev-parse", "--verify", revision + "^{commit}").decode().strip()
    tree = git(root, "rev-parse", commit + "^{tree}").decode().strip()
    return commit, tree


def build(root: Path, commit: str, destination: Path) -> dict:
    verify(root, commit)
    files = entries(root, commit)
    with destination.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as archive:
                for path, (mode, oid) in sorted(files.items()):
                    data = content(root, path, oid, commit)
                    info = tarfile.TarInfo(path)
                    info.size = len(data)
                    info.mode = 0o755 if mode == "100755" else 0o644
                    info.uid = info.gid = info.mtime = 0
                    info.uname = info.gname = ""
                    archive.addfile(info, io.BytesIO(data))
    with destination.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"schema_version": 1, "source_commit": commit,
            "source_tree": identity(root, commit)[1], "policy": POLICY,
            "archive_sha256": digest, "archive_size_bytes": destination.stat().st_size,
            "tracked_file_count": len(files),
            "manifest_sha256": hashlib.sha256(content(root, MANIFEST, files[MANIFEST][1], commit)).hexdigest()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--revision", required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--record", type=Path, required=True)
    args = parser.parse_args()
    try:
        root = repository(args.repo)
        commit, _ = identity(root, args.revision)
        if args.command == "create":
            if args.output is None or args.output.resolve() == args.record.resolve():
                raise ManifestError("create needs a distinct --output and --record")
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(dir=args.output.parent) as temporary:
                path = Path(temporary) / "source.tar.gz"
                record = build(root, commit, path)
                path.replace(args.output)
            args.record.parent.mkdir(parents=True, exist_ok=True)
            args.record.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
        else:
            if args.archive is None:
                raise ManifestError("verify needs --archive")
            with tempfile.TemporaryDirectory() as temporary:
                record = build(root, commit, Path(temporary) / "expected.tar.gz")
            supplied = json.loads(args.record.read_text())
            if supplied != record:
                raise ManifestError("Archive provenance record does not match exact Git revision/canonical archive")
            with args.archive.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest != record["archive_sha256"] or args.archive.stat().st_size != record["archive_size_bytes"]:
                raise ManifestError("Archive differs from canonical exact Git tree")
        print(json.dumps({"status": "PASS", **record}, sort_keys=True))
    except (ManifestError, OSError, ValueError) as exc:
        print(f"source snapshot: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
