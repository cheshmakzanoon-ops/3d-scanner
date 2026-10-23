"""Source adaptation notice must survive binary redistribution in both variants."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/dependencies"))
import source_notices


def test_complete_source_notice_is_reproducible_and_indexed(tmp_path):
    evidence = ROOT / "tools/dependencies/evidence"
    source_notices.assemble(tmp_path, evidence)
    for record in json.loads((evidence / "native-source-notices.json").read_text()):
        text = (tmp_path / record["file"]).read_bytes()
        assert b"Copyright (C) 2014, Samson Yilma" in text
        assert b"Copyright (C) 2018, Intel Corporation" in text
        assert b"Redistribution's in binary form" in text
        assert b"the use of this software" in text
        assert (ROOT / record["adaptedSource"]).read_bytes().startswith(text.rstrip())
        for variant in ("main", "debug"):
            assets = ROOT / f"app/src/{variant}/assets/third-party-notices"
            index = json.loads((assets / "NOTICE_INDEX.json").read_text())
            assert (assets / record["file"]).read_bytes() == text
            assert index["files"][record["file"]] == hashlib.sha256(text).hexdigest()
