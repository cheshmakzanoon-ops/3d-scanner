"""Real CLI entrypoints and filesystem effects; synthetic reports are not scan evidence."""
import json
import os
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_loss_report as report
from tools.qa import dense_profile_sweep as sweep
from tools.qa import report_io


@pytest.fixture
def dense_artifact(tmp_path):
    views = []
    for index in range(2):
        views.append({
            "referenceView": index,
            "photometric": {
                "coverage": "COMPLETE", "sampledSamples": 10,
                "unknownSamples": 0, "evaluatedSamples": 10, "acceptedSamples": 10,
                "rejectionCounts": {reason: 0 for reason in report.REASONS},
                "validReferenceMeanHistogram": [10] + [0] * 15,
                "validLowVarianceMeanHistogram": [0] * 16,
            },
        })
    path = tmp_path / "original.json"
    path.write_text(json.dumps({"lossDiagnostics": {
        "schema": 1, "scope": "ORIGINATING_DENSE_BRANCHES", "available": True,
        "viewCount": 2, "views": views,
    }}), encoding="utf-8")
    return path


@pytest.mark.parametrize("alias", ["same", "symlink", "hardlink"])
def test_loss_cli_refuses_input_aliases_and_preserves_original(dense_artifact, alias, capsys):
    original = dense_artifact.read_bytes()
    output = dense_artifact
    if alias != "same":
        output = dense_artifact.with_name("alias.json")
        if alias == "symlink":
            output.symlink_to(dense_artifact.name)
        else:
            os.link(dense_artifact, output)
    assert report.main(["--input", str(dense_artifact), "--json-out", str(output)]) == 1
    captured = capsys.readouterr()
    assert not captured.out and "INVALID:" in captured.err
    assert dense_artifact.read_bytes() == original
    assert output.read_bytes() == original


@pytest.mark.parametrize("existing", ["receipt", "dangling_symlink", "directory"])
def test_loss_cli_does_not_replace_existing_destinations(dense_artifact, existing, capsys):
    output = dense_artifact.with_name("receipt.json")
    if existing == "receipt":
        output.write_bytes(b"retained prior evidence")
    elif existing == "directory":
        output.mkdir()
    else:
        output.symlink_to("absent-target.json")
    assert report.main(["--input", str(dense_artifact), "--json-out", str(output)]) == 1
    assert not capsys.readouterr().out
    if existing == "receipt":
        assert output.read_bytes() == b"retained prior evidence"
    elif existing == "directory":
        assert output.is_dir() and not list(output.iterdir())
    else:
        assert output.is_symlink() and os.readlink(output) == "absent-target.json"


@pytest.mark.parametrize("payload", [b"\xff\xfe\x80", b"{bad json", None])
def test_loss_cli_read_failures_have_no_traceback_or_success_output(tmp_path, payload, capsys):
    source = tmp_path / "input.json"
    if payload is not None:
        source.write_bytes(payload)
    output = tmp_path / "receipt.json"
    assert report.main(["--input", str(source), "--json-out", str(output)]) == 1
    captured = capsys.readouterr()
    assert not captured.out and "INVALID:" in captured.err and "Traceback" not in captured.err
    assert not output.exists()
    if payload is not None:
        assert source.read_bytes() == payload


def test_loss_cli_rejects_excessively_nested_json_without_publication(tmp_path, capsys):
    source = tmp_path / "deep.json"
    payload = b'{"nested":' * 10_000 + b'0' + b'}' * 10_000
    source.write_bytes(payload)
    output = tmp_path / "receipt.json"
    assert report.main(["--input", str(source), "--json-out", str(output)]) == 1
    captured = capsys.readouterr()
    assert not captured.out and "INVALID:" in captured.err and "Traceback" not in captured.err
    assert source.read_bytes() == payload and not output.exists()
    assert not list(tmp_path.glob("*.partial"))


def test_loss_cli_requires_existing_output_parent(dense_artifact, capsys):
    output = dense_artifact.parent / "missing" / "receipt.json"
    original = dense_artifact.read_bytes()
    assert report.main(["--input", str(dense_artifact), "--json-out", str(output)]) == 1
    assert not capsys.readouterr().out
    assert not output.parent.exists() and dense_artifact.read_bytes() == original


def test_loss_cli_publishes_complete_json_without_temporary_residue(dense_artifact, capsys):
    output = dense_artifact.with_name("receipt.json")
    original = dense_artifact.read_bytes()
    assert report.main(["--input", str(dense_artifact), "--json-out", str(output)]) == 0
    assert "Photometric depth loss" in capsys.readouterr().out
    assert json.loads(output.read_text())["totals"]["accepted"] == 20
    assert dense_artifact.read_bytes() == original
    assert not list(output.parent.glob("*.partial"))


@pytest.mark.parametrize("operation", ["fsync", "link"])
def test_loss_cli_publication_failure_leaves_no_partial_receipt(dense_artifact, monkeypatch, operation, capsys):
    def fail(*args, **kwargs):
        raise OSError("injected write/publication failure")
    monkeypatch.setattr(report_io.os, operation, fail)
    output = dense_artifact.with_name("receipt.json")
    original = dense_artifact.read_bytes()
    assert report.main(["--input", str(dense_artifact), "--json-out", str(output)]) == 1
    captured = capsys.readouterr()
    assert not captured.out and "INVALID:" in captured.err
    assert not output.exists() and dense_artifact.read_bytes() == original
    assert not list(output.parent.glob("*.partial"))


def test_loss_cli_preserves_destination_created_between_preflight_and_publication(dense_artifact, monkeypatch, capsys):
    real_link = report_io.os.link
    output = dense_artifact.with_name("receipt.json")
    winner = b"another writer's completed evidence"
    def race(source, destination):
        Path(destination).write_bytes(winner)
        return real_link(source, destination)
    monkeypatch.setattr(report_io.os, "link", race)
    assert report.main(["--input", str(dense_artifact), "--json-out", str(output)]) == 1
    assert output.read_bytes() == winner
    assert not capsys.readouterr().out and not list(output.parent.glob("*.partial"))


@pytest.fixture
def synthetic_sweep(monkeypatch):
    """Exercise CLI publication only; this fixture never claims native execution."""
    baseline = sweep.Metrics(
        profile="56x3", evaluated=100, accepted=50, rejected=50,
        ambiguous_minimum=40, boundary_edge=10, ncc=0, supported_normals=10,
        fusion_candidates=100, fused_observations=0, vertex_count=1000,
        triangle_count=1000, boundary_edges=1000, intersections=558,
        nonmanifold_edges=0, intersection_budget_exceeded=False,
    )
    monkeypatch.setattr(sweep, "sweep", lambda *args: ([baseline], {"56x3": "BASELINE"}))


def test_sweep_cli_refuses_existing_receipt_before_native_work(tmp_path, monkeypatch, capsys):
    output = tmp_path / "sweep.json"
    output.write_bytes(b"original evidence")
    def must_not_run(*args):
        raise AssertionError("A conflicting destination must be rejected before reconstruction")
    monkeypatch.setattr(sweep, "sweep", must_not_run)
    assert sweep.main(["--json-out", str(output)]) == 1
    assert output.read_bytes() == b"original evidence" and not capsys.readouterr().out


def test_sweep_cli_native_value_error_is_invalid_without_publication(tmp_path, monkeypatch, capsys):
    """Exercise the entrypoint error boundary, not a successful native measurement."""
    def native_failure(*args):
        raise ValueError("Native surface reconstruction failed")
    monkeypatch.setattr(sweep, "sweep", native_failure)
    output = tmp_path / "sweep.json"
    assert sweep.main(["--json-out", str(output)]) == 1
    captured = capsys.readouterr()
    assert not captured.out and "INVALID: Native surface reconstruction failed" in captured.err
    assert "Traceback" not in captured.err and not output.exists()
    assert not list(tmp_path.glob("*.partial"))


def test_sweep_cli_publishes_complete_receipt(synthetic_sweep, tmp_path, capsys):
    output = tmp_path / "sweep.json"
    assert sweep.main(["--json-out", str(output)]) == 0
    assert json.loads(output.read_text())["profiles"][0]["decision"] == "BASELINE"
    assert "Depth profile sweep" in capsys.readouterr().out
    assert not list(tmp_path.glob("*.partial"))


def test_sweep_cli_write_failure_has_no_success_output(synthetic_sweep, tmp_path, monkeypatch, capsys):
    def fail(*args, **kwargs):
        raise OSError("injected full filesystem")
    monkeypatch.setattr(report_io.os, "fsync", fail)
    output = tmp_path / "sweep.json"
    assert sweep.main(["--json-out", str(output)]) == 1
    assert not output.exists() and not capsys.readouterr().out
    assert not list(tmp_path.glob("*.partial"))


def test_receipt_serialization_failure_never_creates_output(tmp_path):
    output = tmp_path / "invalid.json"
    with pytest.raises(ValueError):
        report_io.write_json_receipt(output, {"invalid": float("nan")})
    assert not list(tmp_path.iterdir())
