"""Parity harness failure controls; tiny artifact fixtures are not native evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import surface_diagnostics_parity as parity


def artifacts(folder):
    folder.mkdir(parents=True)
    # Deliberately minimal comparison inputs, never a scanner-quality fixture.
    result = {
        "mesh": {"vertexCount": 3, "triangleCount": 1, "vertices": [[0, 0, 1]] * 3,
                 "triangles": [[0, 1, 2]], "lossDiagnostics": {"schema": 3}},
        "dense": {"count": 3, "lossDiagnostics": {"accepted": 3}},
        "flag": False,
    }
    parity.write_new_json(folder / "result.json", result)
    parity.write_new_json(folder / "fixture.json", {"synthetic": True, "name": "comparison-control"})
    (folder / "surface.glb").write_bytes(b"glTF-comparison-control")
    (folder / "surface.ply").write_bytes(b"ply\ncomparison-control")
    return result


def replace_result(folder, result):
    (folder / "result.json").write_text(json.dumps(result))


def test_only_diagnostic_subtrees_are_excluded():
    source = {"mesh": {"vertices": [1, 2], "lossDiagnostics": {"schema": 4}},
              "other": [{"lossDiagnostics": 9, "kept": 1}], "diagnostic": "keep"}
    original = copy.deepcopy(source)
    assert parity.without_loss_diagnostics(source) == {
        "mesh": {"vertices": [1, 2]}, "other": [{"kept": 1}], "diagnostic": "keep"}
    assert source == original


def test_unchanged_geometry_and_exports_with_new_diagnostics_compare_equal(tmp_path):
    left, right = tmp_path / "baseline", tmp_path / "candidate"
    artifacts(left)
    result = artifacts(right)
    result["mesh"]["lossDiagnostics"] = {"schema": 4, "additional": [1, 2, 3]}
    result["dense"]["lossDiagnostics"] = None
    replace_result(right, result)
    receipt = parity.compare_case(left, right)
    assert receipt["status"] == "PASS"
    assert receipt["nonDiagnosticFieldsEqual"]
    assert all(row["bytesEqual"] for row in receipt["exports"].values())
    assert len(receipt["fixtureSha256"]) == 64


@pytest.mark.parametrize("change", ["coordinate", "triangle", "metadata", "bool-to-int", "added-field"])
def test_every_non_diagnostic_change_blocks_parity(tmp_path, change):
    left, right = tmp_path / "baseline", tmp_path / "candidate"
    artifacts(left)
    result = artifacts(right)
    if change == "coordinate":
        result["mesh"]["vertices"][0][0] = .001
    elif change == "triangle":
        result["mesh"]["triangles"] = [[0, 2, 1]]
    elif change == "metadata":
        result["dense"]["count"] = 2
    elif change == "bool-to-int":
        result["flag"] = 0
    else:
        result["added"] = "value"
    replace_result(right, result)
    assert parity.compare_case(left, right)["status"] == "FAIL"


@pytest.mark.parametrize("filename", ["surface.glb", "surface.ply"])
def test_export_bytes_must_match_independently_of_json(tmp_path, filename):
    left, right = tmp_path / "baseline", tmp_path / "candidate"
    artifacts(left)
    artifacts(right)
    path = right / filename
    path.write_bytes(path.read_bytes() + b"changed")
    receipt = parity.compare_case(left, right)
    assert receipt["status"] == "FAIL" and receipt["nonDiagnosticFieldsEqual"]
    assert not receipt["exports"][filename]["bytesEqual"]


@pytest.mark.parametrize("failure", ["missing-mesh", "empty-mesh", "mismatched-count", "bool-count",
                                     "missing-export", "empty-export", "bad-header", "different-input"])
def test_absent_or_incomplete_evidence_never_passes(tmp_path, failure):
    left, right = tmp_path / "baseline", tmp_path / "candidate"
    artifacts(left)
    result = artifacts(right)
    if failure == "missing-mesh":
        del result["mesh"]
    elif failure == "empty-mesh":
        result["mesh"]["vertexCount"] = 0
        result["mesh"]["vertices"] = []
    elif failure == "mismatched-count":
        result["mesh"]["triangleCount"] = 2
    elif failure == "bool-count":
        result["mesh"]["triangleCount"] = True
    elif failure == "missing-export":
        (right / "surface.ply").unlink()
    elif failure == "empty-export":
        (right / "surface.ply").write_bytes(b"")
    elif failure == "bad-header":
        (right / "surface.glb").write_bytes(b"invalid")
    else:
        (right / "fixture.json").write_text('{"synthetic":true,"name":"different"}')
    replace_result(right, result)
    with pytest.raises(ValueError):
        parity.compare_case(left, right)


def test_nonfinite_results_cannot_compare_equal(tmp_path):
    path = tmp_path / "result.json"
    path.write_text('{"value":NaN}')
    with pytest.raises(ValueError, match="Nonfinite"):
        parity.read_json(path)


def test_children_inherit_sanitizer_environment_and_preserve_failure(tmp_path, monkeypatch):
    monkeypatch.setenv("LD_PRELOAD", "/synthetic/test/runtime.so")
    monkeypatch.setenv("ASAN_OPTIONS", "detect_leaks=1:halt_on_error=1")
    seen = {}

    def fake_process(command, **kwargs):
        seen.update(command=command, **kwargs)
        kwargs["stdout"].write(b"synthetic nonzero-exit control\n")
        return subprocess.CompletedProcess(command, 17)

    monkeypatch.setattr(parity.subprocess, "run", fake_process)
    result = parity.run_child(tmp_path / "library.so", parity.FIXTURES[0], tmp_path / "baseline" / "case", 9)
    assert "env" not in seen  # No sanitizer stripping or replacement environment.
    assert seen["timeout"] == 9 and seen["cwd"] == ROOT
    assert seen["command"][:3] == [sys.executable, "-m", "tools.qa.surface_diagnostics_parity"]
    assert result["returnCode"] == 17 and not result["timedOut"]


def test_timeout_cannot_be_treated_as_completion(tmp_path, monkeypatch):
    def timeout(command, **kwargs):
        raise subprocess.TimeoutExpired(command, kwargs["timeout"])

    monkeypatch.setattr(parity.subprocess, "run", timeout)
    result = parity.run_child(tmp_path / "library.so", parity.FIXTURES[0], tmp_path / "candidate" / "case", 1)
    assert result["timedOut"] and result["returnCode"] is None


def test_artifacts_cannot_override_nonzero_process_completion(tmp_path, monkeypatch):
    library = tmp_path / "library.so"
    library.write_bytes(b"synthetic execution-control placeholder; not loaded")

    def failed_after_output(_library, _name, folder, _timeout):
        artifacts(folder)
        return {"returnCode": 17, "timedOut": False}

    monkeypatch.setattr(parity, "run_child", failed_after_output)
    receipt = parity.run(library, library, tmp_path / "evidence")
    assert receipt["status"] == "FAIL"
    assert len(receipt["cases"]) == len(parity.FIXTURES)
    assert all(case["status"] == "FAIL" for case in receipt["cases"])
    assert all("nonDiagnosticFieldsEqual" not in case for case in receipt["cases"])


def test_existing_output_preserved_before_any_native_execution(tmp_path):
    library = tmp_path / "library.so"
    library.write_bytes(b"not-executed-placeholder")
    output = tmp_path / "evidence"
    output.mkdir()
    original = output / "receipt.json"
    original.write_text("retained evidence")
    with pytest.raises(FileExistsError):
        parity.run(library, library, output)
    assert original.read_text() == "retained evidence"


def test_worker_refuses_existing_export_before_loading_library(tmp_path):
    export = tmp_path / "surface.glb"
    export.write_bytes(b"original")
    with pytest.raises(ValueError, match="already exists"):
        parity.worker(tmp_path / "missing-library.so", parity.FIXTURES[0], tmp_path)
    assert export.read_bytes() == b"original"


@pytest.mark.parametrize("timeout", [0, 121, True, 1.5])
def test_unbounded_or_wrong_timeout_rejected_before_output(tmp_path, timeout):
    output = tmp_path / "output"
    with pytest.raises(ValueError, match="Timeout"):
        parity.run(tmp_path / "a", tmp_path / "b", output, timeout)
    assert not output.exists()


def test_fixture_identities_bind_mask_and_view_count():
    pytest.importorskip("numpy")
    records = [parity.fixture(name)[2] for name in parity.FIXTURES]
    assert [row["rgbaShape"][0] for row in records] == [3, 3, 2]
    assert len({row["rgbaSha256"] for row in records}) == 3
    assert records[0]["cameraFloat64LittleEndianSha256"] == records[1]["cameraFloat64LittleEndianSha256"]
    assert records[0]["cameraFloat64LittleEndianSha256"] != records[2]["cameraFloat64LittleEndianSha256"]
    assert all(row["synthetic"] and row["settings"] == parity.SETTINGS for row in records)


def test_missing_library_cli_reports_failure_without_partial_evidence(tmp_path, capsys):
    output = tmp_path / "output"
    assert parity.main(["--baseline-library", str(tmp_path / "missing"), "--candidate-library",
                        str(tmp_path / "missing"), "--output", str(output)]) == 1
    captured = capsys.readouterr()
    assert "PARITY_FAILED" in captured.err and not captured.out
    assert not output.exists()


@pytest.fixture
def clean_parity_checkout(tmp_path, monkeypatch):
    """Real git identity with controlled artifacts, never native qualification."""
    source = tmp_path / "source"
    for name in ("tools/qa/surface_diagnostics_parity.py", "tools/qa/dense_profile_sweep.py",
                 "tools/native_api.py"):
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# synthetic harness identity control\n")
    subprocess.run(["git", "init", "-q", str(source)], check=True, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=source, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=Parity control", "-c",
                    "user.email=parity-control@example.invalid", "commit", "-qm", "control"],
                   cwd=source, check=True, capture_output=True)
    monkeypatch.setattr(parity, "ROOT", source)
    monkeypatch.setattr(parity, "__file__", str(source / "tools/qa/surface_diagnostics_parity.py"))
    library = tmp_path / "library.so"
    library.write_bytes(b"controlled comparison placeholder; never loaded")
    calls = []

    def complete_control(_library, _name, folder, _timeout):
        calls.append(folder)
        artifacts(folder)
        return {"returnCode": 0, "timedOut": False}

    monkeypatch.setattr(parity, "run_child", complete_control)
    assert parity.source_identity()["dirty"] is False
    return source, library, calls


@pytest.mark.parametrize("path_kind", ["child", "dotdot", "root"])
def test_source_contained_output_is_rejected_before_creation(clean_parity_checkout, tmp_path, path_kind):
    source, library, calls = clean_parity_checkout
    output = source / "reports/parity"
    if path_kind == "dotdot":
        output = tmp_path / "unused/../source/reports/parity"
    elif path_kind == "root":
        output = source
    before = parity.source_identity()
    with pytest.raises(ValueError, match="outside the source repository"):
        parity.run(library, library, output)
    assert not calls and not (source / "reports").exists() and not (tmp_path / "unused").exists()
    assert parity.source_identity() == before


def test_external_evidence_preserves_clean_checkout_identity(clean_parity_checkout, tmp_path):
    source, library, calls = clean_parity_checkout
    before = parity.source_identity()
    output = tmp_path / "external/evidence"
    receipt = parity.run(library, library, output)
    assert receipt["status"] == "PASS"
    assert len(calls) == len(parity.FIXTURES) * 2
    assert receipt["harnessSource"] == before == parity.source_identity()
    assert json.loads((output / "receipt.json").read_text())["status"] == "PASS"


@pytest.mark.parametrize("mutation", ["helper", "library"])
def test_external_output_does_not_waive_real_input_changes(
        clean_parity_checkout, tmp_path, monkeypatch, mutation):
    source, library, _calls = clean_parity_checkout
    original_child = parity.run_child
    changed = False

    def mutate_then_complete(*args):
        nonlocal changed
        if not changed:
            target = source / "tools/native_api.py" if mutation == "helper" else library
            target.write_bytes(target.read_bytes() + b"changed during verification\n")
            changed = True
        return original_child(*args)

    monkeypatch.setattr(parity, "run_child", mutate_then_complete)
    receipt = parity.run(library, library, tmp_path / "external/evidence")
    assert all(case["status"] == "PASS" for case in receipt["cases"])
    assert receipt["status"] == "FAIL"
    assert "Harness source or native library changed" in receipt["failure"]


@pytest.mark.parametrize("symlink_kind", ["ancestor", "dangling-output"])
def test_symlink_output_paths_are_rejected_before_creation(tmp_path, symlink_kind):
    target = tmp_path / "target"
    target.mkdir()
    alias = tmp_path / "alias"
    if symlink_kind == "ancestor":
        alias.symlink_to(target, target_is_directory=True)
        output = alias / "evidence"
    else:
        alias.symlink_to(target / "absent", target_is_directory=True)
        output = alias
    with pytest.raises(ValueError, match="must not traverse symlinks"):
        parity.run(tmp_path / "not-loaded.so", tmp_path / "not-loaded.so", output)
    assert list(target.iterdir()) == []


def test_direct_worker_cannot_write_into_source(clean_parity_checkout):
    source, library, calls = clean_parity_checkout
    with pytest.raises(ValueError, match="outside the source repository"):
        parity.worker(library, parity.FIXTURES[0], source / "reports/parity")
    assert not calls and not (source / "reports").exists()
