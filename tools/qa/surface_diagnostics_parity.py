#!/usr/bin/env python3
"""Compare diagnostic-only native changes on bounded synthetic photographs.

Each library runs in its own ordinary-exit Python process. Every result field
except ``lossDiagnostics`` must match, along with the actual GLB and PLY bytes.
This is synthetic equivalence evidence, not reconstruction-quality acceptance.

Usage: python -m tools.qa.surface_diagnostics_parity --baseline-library OLD.so
       --candidate-library NEW.so --output /outside/repository/NEW_DIRECTORY

The output must be a new directory outside the source repository so evidence
creation cannot change the harness source identity during verification.

Exit codes: 0 complete parity, 1 failed or unavailable verification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ("textured-three-view", "masked-three-view", "textured-two-view")
SETTINGS = {"near": 3.1, "far": 7.0, "planes": 56, "stride": 3}
CLASSIFICATION = "SYNTHETIC_SURFACE_DIAGNOSTIC_PARITY_NOT_QUALITY"
MAX_JSON_BYTES = 32 * 1024 * 1024


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def without_loss_diagnostics(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: without_loss_diagnostics(item) for key, item in value.items()
                if key != "lossDiagnostics"}
    if isinstance(value, list):
        return [without_loss_diagnostics(item) for item in value]
    return value


def write_new_json(path: Path, value: Any) -> None:
    encoded = json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"
    with path.open("x", encoding="utf-8") as stream:
        stream.write(encoded)


def read_json(path: Path) -> Any:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError(f"Missing or oversized regular JSON artifact: {path.name}")

    def nonfinite(value: str) -> None:
        raise ValueError(f"Nonfinite JSON number: {value}")

    return json.loads(path.read_text(encoding="utf-8"), parse_constant=nonfinite)


def source_identity() -> dict[str, Any]:
    # This identifies the harness, not an unverified build provenance for a .so.
    paths = (Path(__file__).resolve(), ROOT / "tools/qa/dense_profile_sweep.py",
             ROOT / "tools/native_api.py")
    return {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
        "files": {str(path.relative_to(ROOT)): digest(path) for path in paths},
    }


def fixture(name: str) -> tuple[Any, Any, dict[str, Any]]:
    if name not in FIXTURES:
        raise ValueError("Unknown synthetic fixture")
    from tools.qa.dense_profile_sweep import synthetic_fixture

    images, cameras = synthetic_fixture()
    if name == "masked-three-view":
        images[-1, 16:60, 21:70, 3] = 0
    elif name == "textured-two-view":
        images, cameras = images[:2].copy(), cameras[:2].copy()
    identity = {
        "name": name, "synthetic": True, "seed": 504, "settings": SETTINGS,
        "rgbaShape": list(images.shape), "cameraShape": list(cameras.shape),
        "rgbaSha256": hashlib.sha256(images.tobytes(order="C")).hexdigest(),
        "cameraFloat64LittleEndianSha256": hashlib.sha256(
            cameras.astype("<f8", copy=False).tobytes(order="C")
        ).hexdigest(),
    }
    return images, cameras, identity


def require_surface(result: Any) -> None:
    if not isinstance(result, dict) or not isinstance(result.get("mesh"), dict):
        raise ValueError("Native fixture did not produce a mesh")
    mesh = result["mesh"]
    for count_name, array_name in (("vertexCount", "vertices"), ("triangleCount", "triangles")):
        count, values = mesh.get(count_name), mesh.get(array_name)
        if type(count) is not int or count <= 0 or not isinstance(values, list) or len(values) != count:
            raise ValueError(f"Native mesh has no complete nonempty {array_name}")


def export_record(path: Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file() or not path.stat().st_size:
        raise ValueError(f"Missing nonempty export: {path.name}")
    with path.open("rb") as stream:
        prefix = stream.read(4)
    if prefix != (b"glTF" if path.suffix == ".glb" else b"ply\n"):
        raise ValueError(f"Unexpected export header: {path.name}")
    return {"bytes": path.stat().st_size, "sha256": digest(path)}


def external_output_path(output: Path) -> Path:
    output = output.absolute()
    if any(path.is_symlink() for path in (output, *output.parents)):
        raise ValueError("Output directory must not traverse symlinks")
    output = output.resolve()
    if output.is_relative_to(ROOT.resolve()):
        raise ValueError("Output directory must be outside the source repository")
    return output


def worker(library: Path, name: str, folder: Path) -> None:
    folder = external_output_path(folder)
    from tools.native_api import NativeEngine

    # These paths are inside the parent's fresh directory. Reject standalone
    # worker reuse before the native exporter could overwrite an existing file.
    for filename in ("surface.glb", "surface.ply", "result.json", "fixture.json"):
        path = folder / filename
        if path.exists() or path.is_symlink():
            raise ValueError("Worker output already exists")
    images, cameras, identity = fixture(name)
    result = NativeEngine(library).surface(
        images, cameras, SETTINGS["near"], SETTINGS["far"], SETTINGS["planes"],
        SETTINGS["stride"], glb=folder / "surface.glb", ply=folder / "surface.ply",
    )
    require_surface(result)
    for filename in ("surface.glb", "surface.ply"):
        export_record(folder / filename)
    write_new_json(folder / "result.json", result)
    write_new_json(folder / "fixture.json", identity)


def run_child(library: Path, name: str, folder: Path, timeout: int) -> dict[str, Any]:
    folder.mkdir(parents=True)
    command = [sys.executable, "-m", "tools.qa.surface_diagnostics_parity", "_worker",
               "--library", str(library), "--fixture", name, "--output", str(folder)]
    log = folder / "process.log"
    timed_out = False
    with log.open("xb") as stream:
        try:
            # Inherit sanitizer/preload settings unchanged. A failing ordinary
            # interpreter exit is a failure, even after artifacts were written.
            completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                       timeout=timeout, check=False)
            code = completed.returncode
        except subprocess.TimeoutExpired:
            code, timed_out = None, True
    return {"returnCode": code, "timedOut": timed_out, "timeoutSeconds": timeout,
            "log": str(log.relative_to(folder.parent.parent)), "logSha256": digest(log)}


def compare_case(baseline: Path, candidate: Path) -> dict[str, Any]:
    left, right = read_json(baseline / "result.json"), read_json(candidate / "result.json")
    require_surface(left)
    require_surface(right)
    left_fixture, right_fixture = read_json(baseline / "fixture.json"), read_json(candidate / "fixture.json")
    if canonical(left_fixture) != canonical(right_fixture):
        raise ValueError("Baseline and candidate did not evaluate identical fixture inputs")
    values = [canonical(without_loss_diagnostics(result)) for result in (left, right)]
    exports = {}
    for filename in ("surface.glb", "surface.ply"):
        first, second = export_record(baseline / filename), export_record(candidate / filename)
        # Compare the actual bytes as well as retaining their independent hashes.
        same = (baseline / filename).read_bytes() == (candidate / filename).read_bytes()
        exports[filename] = {"baseline": first, "candidate": second, "bytesEqual": same}
    equal = values[0] == values[1]
    return {
        "fixture": left_fixture, "fixtureSha256": hashlib.sha256(canonical(left_fixture)).hexdigest(),
        "nonDiagnosticFieldsEqual": equal,
        "nonDiagnosticSha256": {"baseline": hashlib.sha256(values[0]).hexdigest(),
                                "candidate": hashlib.sha256(values[1]).hexdigest()},
        "exports": exports,
        "status": "PASS" if equal and all(row["bytesEqual"] for row in exports.values()) else "FAIL",
    }


def run(baseline_library: Path, candidate_library: Path, output: Path, timeout: int = 60) -> dict[str, Any]:
    if type(timeout) is not int or not 1 <= timeout <= 120:
        raise ValueError("Timeout must be 1 to 120 seconds per child")
    # Evidence in a clean checkout would itself flip git's dirty identity.
    # Reject it instead of weakening the before/after source comparison.
    output = external_output_path(output)
    libraries = {"baseline": baseline_library.resolve(strict=True),
                 "candidate": candidate_library.resolve(strict=True)}
    records = {}
    for label, path in libraries.items():
        if not path.is_file():
            raise ValueError("Native library must be a regular file")
        records[label] = {"path": str(path), "bytes": path.stat().st_size, "sha256": digest(path)}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir()  # Never mix runs or replace previous evidence.
    source = source_identity()
    receipt: dict[str, Any] = {
        "schema": 1, "classification": CLASSIFICATION, "status": "FAIL",
        "harnessSource": source, "libraries": records, "python": sys.version,
        "cases": [], "limitations": [
            "Synthetic same-input equivalence only; not physical accuracy, coverage or device qualification",
            "Only lossDiagnostics fields are excluded from exact native-result comparison",
            "Library hashes identify tested bytes; their build-source provenance is not inferred from harness HEAD",
        ],
    }
    try:
        for name in FIXTURES:
            case: dict[str, Any] = {"name": name, "status": "FAIL", "processes": {}}
            receipt["cases"].append(case)
            for label, library in libraries.items():
                case["processes"][label] = run_child(library, name, output / label / name, timeout)
            if any(row["returnCode"] != 0 or row["timedOut"] for row in case["processes"].values()):
                case["failure"] = "Native child failed or timed out; artifacts do not establish completion"
                continue
            case.update(compare_case(output / "baseline" / name, output / "candidate" / name))
        if source_identity() != source or any(digest(path) != records[label]["sha256"]
                                             for label, path in libraries.items()):
            raise ValueError("Harness source or native library changed during verification")
        if len(receipt["cases"]) == len(FIXTURES) and all(case["status"] == "PASS" for case in receipt["cases"]):
            receipt["status"] = "PASS"
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        receipt["failure"] = str(failure)
    write_new_json(output / "receipt.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    child = bool(argv and argv[0] == "_worker")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    if child:
        parser.add_argument("--library", required=True, type=Path)
        parser.add_argument("--fixture", required=True, choices=FIXTURES)
    else:
        parser.add_argument("--baseline-library", required=True, type=Path)
        parser.add_argument("--candidate-library", required=True, type=Path)
        parser.add_argument("--timeout", type=int, default=60, help="Seconds per native child, 1..120")
    parser.add_argument("--output", required=True, type=Path,
                        help="New evidence directory outside the source repository")
    args = parser.parse_args(argv[1:] if child else argv)
    try:
        if child:
            worker(args.library, args.fixture, args.output)
            return 0
        result = run(args.baseline_library, args.candidate_library, args.output, args.timeout)
        print(json.dumps({"status": result["status"], "receipt": str(args.output / "receipt.json")}))
        return 0 if result["status"] == "PASS" else 1
    except (ImportError, OSError, ValueError, subprocess.SubprocessError) as failure:
        print(f"PARITY_FAILED: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
