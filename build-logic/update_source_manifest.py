#!/usr/bin/env python3
"""Regenerate SOURCE_MANIFEST.sha256 from stage-0 index blobs; stage all intended changes first."""
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "SOURCE_MANIFEST.sha256"
entries = []
for record in subprocess.check_output(["git", "ls-files", "--stage", "-z"], cwd=ROOT).split(b"\0"):
    if not record:
        continue
    metadata, encoded_path = record.split(b"\t", 1)
    mode, oid, stage = metadata.split()
    if stage != b"0":
        raise SystemExit("Resolve every unmerged index entry before generating the manifest.")
    path = encoded_path.decode("utf-8")
    if "\n" in path or "\r" in path:
        raise SystemExit("Manifest filenames cannot contain line breaks.")
    if path != MANIFEST:
        entries.append((path, oid))

lines = []
with subprocess.Popen(["git", "cat-file", "--batch"], cwd=ROOT,
                      stdin=subprocess.PIPE, stdout=subprocess.PIPE) as objects:
    for path, oid in sorted(entries):
        objects.stdin.write(oid + b"\n")
        objects.stdin.flush()
        actual_oid, kind, encoded_size = objects.stdout.readline().split()
        if actual_oid != oid or kind != b"blob":
            raise SystemExit(f"Index entry is not a regular Git blob: {path}")
        remaining = int(encoded_size)
        digest = hashlib.sha256()
        while remaining:
            chunk = objects.stdout.read(min(65536, remaining))
            if not chunk:
                raise SystemExit(f"Incomplete Git blob: {path}")
            digest.update(chunk)
            remaining -= len(chunk)
        if objects.stdout.read(1) != b"\n":
            raise SystemExit("Invalid git cat-file framing.")
        lines.append(f"{digest.hexdigest()}  {path}\n")
    objects.stdin.close()
    if objects.wait() != 0:
        raise SystemExit("git cat-file failed.")
(ROOT / MANIFEST).write_text("".join(lines), encoding="utf-8", newline="\n")
print(f"Wrote {len(lines)} staged-blob checksums. Stage {MANIFEST} before committing.")
