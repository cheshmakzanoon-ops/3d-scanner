"""Publish complete QA receipts without replacing existing evidence or inputs."""
from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from typing import Any


def validate_receipt_path(path: Path) -> None:
    """Require a new filename, including refusal of dangling symlink entries.

    Existing input aliases (the same path, symlinks and hardlinks) are thereby
    protected along with previously written receipts. Publication repeats this
    check atomically, so a file created after this preflight is also preserved.
    """
    if os.path.lexists(path):
        raise FileExistsError(f"Receipt path already exists; choose a new path: {path}")
    if not path.parent.is_dir():
        raise NotADirectoryError(f"Receipt parent is not an existing directory: {path.parent}")


def write_json_receipt(path: Path, document: Any) -> None:
    """Write a sibling temporary file, then atomically publish without clobbering.

    A same-directory hard link makes the complete file visible only if the final
    name is still absent. Unlike replace/rename, this cannot overwrite an input
    alias or another writer's receipt. Filesystems without hard-link support fail
    explicitly instead of falling back to a partially visible or replacing write.
    """
    validate_receipt_path(path)
    encoded = json.dumps(document, indent=2, sort_keys=True, allow_nan=False) + "\n"
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".partial", dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
