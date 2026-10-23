"""Reproduce reviewed file-specific notices for adapted native source components."""
import hashlib
import json
from pathlib import Path


def assemble(output: Path, evidence: Path) -> None:
    records = json.loads((evidence / "native-source-notices.json").read_text())
    root = evidence.parent / "source-notices"
    for record in records:
        name = record["file"]
        if Path(name).name != name:
            raise ValueError("Invalid source notice path")
        data = (root / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != record["sha256"]:
            raise ValueError("Changed reviewed native source notice: " + name)
        (output / name).write_bytes(data)
