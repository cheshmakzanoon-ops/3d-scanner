"""Byte-bound receipts and mutation refusal, including the actual native path."""
from dataclasses import replace
import hashlib
import importlib
import json
from pathlib import Path
import shutil
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_loss_report as loss
from tools.qa import dense_profile_sweep as sweep
from tools.qa import dense_receipt_provenance as provenance


def dense_document():
    return {"privateMetadata": "photographs / café", "lossDiagnostics": {
        "schema": 1, "scope": "ORIGINATING_DENSE_BRANCHES", "available": True,
        "viewCount": 2, "views": [{
            "referenceView": index, "photometric": {
                "coverage": "COMPLETE", "sampledSamples": 10, "unknownSamples": 0,
                "evaluatedSamples": 10, "acceptedSamples": 10,
                "rejectionCounts": {name: 0 for name in loss.REASONS},
                "validReferenceMeanHistogram": [10] + [0] * 15,
                "validLowVarianceMeanHistogram": [0] * 16,
            },
        } for index in range(2)],
    }}


def metrics():
    return sweep.Metrics(
        profile="56x3", evaluated=100, accepted=80, rejected=20,
        ambiguous_minimum=2, boundary_edge=8, ncc=10, supported_normals=20,
        fusion_candidates=50, fused_observations=5, vertex_count=100,
        triangle_count=100, boundary_edges=10, intersections=0,
        nonmanifold_edges=0, intersection_budget_exceeded=False,
    )


@pytest.mark.parametrize("indent", [None, 2])
def test_loss_receipt_hashes_exact_parsed_bytes_without_private_path(tmp_path, indent, capsys):
    source = tmp_path / "private-capture-name.json"
    payload = (json.dumps(dense_document(), ensure_ascii=False, indent=indent) + " \n").encode("utf-8")
    source.write_bytes(payload)
    output = tmp_path / "receipt.json"
    assert loss.main(["--input", str(source), "--json-out", str(output)]) == 0
    carried = json.loads(output.read_bytes())
    assert carried["provenance"] == {"input": {
        "sha256": hashlib.sha256(payload).hexdigest(), "byteCount": len(payload),
    }}
    serialized = output.read_text()
    assert str(tmp_path) not in serialized and source.name not in serialized
    assert "privateMetadata" not in serialized and "café" not in serialized
    assert source.read_bytes() == payload
    assert capsys.readouterr().out


def test_loss_provenance_does_not_reread_replaced_input(tmp_path, monkeypatch):
    source, output = tmp_path / "input.json", tmp_path / "receipt.json"
    payload = json.dumps(dense_document()).encode("utf-8")
    source.write_bytes(payload)
    original = loss.build_report

    def parse_then_replace(document):
        result = original(document)
        source.write_bytes(b'{"different":"later input"}')
        return result

    monkeypatch.setattr(loss, "build_report", parse_then_replace)
    assert loss.main(["--input", str(source), "--json-out", str(output)]) == 0
    assert json.loads(output.read_bytes())["provenance"]["input"] == provenance.byte_identity(payload)
    assert source.read_bytes() != payload


@pytest.mark.parametrize("payload", [b'{"value":' + b"9" * 5000 + b"}", b"\xff", b"{"])
def test_malformed_input_does_not_publish_provenance(tmp_path, payload, capsys):
    source, output = tmp_path / "input.json", tmp_path / "receipt.json"
    source.write_bytes(payload)
    assert loss.main(["--input", str(source), "--json-out", str(output)]) == 1
    assert not output.exists() and source.read_bytes() == payload
    captured = capsys.readouterr()
    assert not captured.out and "INVALID:" in captured.err


def test_pure_receipts_do_not_invent_provenance():
    assert loss.receipt(None)["provenance"] is None
    assert sweep.receipt([metrics()], {"56x3": "BASELINE"})["provenance"] is None
    with pytest.raises(loss.MalformedDiagnostics, match="captured execution evidence"):
        sweep.receipt([metrics()], {"56x3": "BASELINE"}, provenance={"execution": "native"})


def test_plain_tuple_cli_result_has_no_native_provenance(tmp_path, monkeypatch):
    monkeypatch.setattr(sweep, "sweep", lambda *args: ([metrics()], {"56x3": "BASELINE"}))
    output = tmp_path / "receipt.json"
    assert sweep.main(["--profile", "56x3", "--json-out", str(output)]) == 0
    assert json.loads(output.read_bytes())["provenance"] is None


@pytest.fixture
def native():
    pytest.importorskip("numpy")
    module = importlib.import_module("tools.native_api")
    try:
        module.library_path()
    except (FileNotFoundError, OSError) as failure:
        pytest.skip(f"native library unavailable: {failure}")
    return module


def test_native_cli_receipt_binds_actual_library_buffers_and_helpers(native, tmp_path, monkeypatch):
    import numpy as np

    observed = []
    original_surface, original_fixture = native.NativeEngine.surface, sweep.synthetic_fixture

    def differently_stored_fixture():
        rgba, cameras = original_fixture()
        return np.asfortranarray(rgba), cameras.astype(np.float32)

    def measured(engine, rgba, cameras, *args):
        assert rgba.flags.c_contiguous and cameras.flags.c_contiguous
        assert rgba.dtype == np.uint8 and cameras.dtype == np.float64
        loaded = Path(engine.library._name)
        observed.append((loaded, loaded.read_bytes(), rgba.tobytes(), cameras.tobytes()))
        return original_surface(engine, rgba, cameras, *args)

    monkeypatch.setattr(sweep, "synthetic_fixture", differently_stored_fixture)
    monkeypatch.setattr(native.NativeEngine, "surface", measured)
    output = tmp_path / "receipt.json"
    assert sweep.main(["--profile", "12x8", "--baseline", "12x8", "--json-out", str(output)]) == 0
    assert len(observed) == 1
    library, library_bytes, rgba, cameras = observed[0]
    carried = json.loads(output.read_bytes())["provenance"]
    assert carried["execution"] == "HOST_CTYPES_NATIVE_SURFACE"
    assert library != native.library_path().resolve()
    assert not library.exists() and not library.parent.exists()
    assert carried["nativeLibrary"] == provenance.byte_identity(library_bytes)
    assert library_bytes == native.library_path().read_bytes()
    assert carried["nativeLibraryLoadIsolation"] == "UNIQUE_VERIFIED_COPY"
    assert carried["nativeLibraryBuildProvenance"] is None
    for field, payload in (("rgba", rgba), ("cameras", cameras)):
        assert carried["fixture"][field]["sha256"] == hashlib.sha256(payload).hexdigest()
        assert carried["fixture"][field]["byteCount"] == len(payload)
        assert carried["fixture"][field]["order"] == "C"
    assert carried["fixture"]["rgba"]["shape"] == [3, 76, 104, 4]
    assert carried["fixture"]["cameras"]["shape"] == [3, 16]
    for name, identity in carried["helperSources"].items():
        assert identity == provenance.byte_identity((ROOT / name).read_bytes())
    assert "tools/qa/dense_receipt_provenance.py" in carried["helperSources"]
    assert carried["sourceGitHEAD"] is None or len(carried["sourceGitHEAD"]) in (40, 64)
    assert carried["sourceGitDirty"] in (True, False, None)
    assert str(ROOT) not in output.read_text() and str(tmp_path) not in output.read_text()


def test_replaced_source_path_does_not_reuse_a_previously_loaded_handle(native, tmp_path, monkeypatch):
    # Both variants are actual native ELF libraries. The trailing bytes make
    # their identities different without requiring a second compiler/toolchain.
    # Keeping the old CDLL alive reproduces dlopen's stale-path cache behavior.
    payload = native.library_path().read_bytes()
    if not payload.startswith(b"\x7fELF"):
        pytest.skip("Stale dlopen pathname regression requires an ELF host library")
    source = tmp_path / "shared.so"
    source.write_bytes(payload + b"previous-loaded-library-identity")
    earlier = native.NativeEngine(source)
    replacement = tmp_path / "replacement.so"
    replacement.write_bytes(payload)
    replacement.replace(source)
    stale = native.NativeEngine(source)
    assert stale.library._handle == earlier.library._handle
    monkeypatch.setenv("RF_NATIVE_LIBRARY", str(source))
    observed = []
    original = native.NativeEngine.surface

    def measured(engine, *args):
        copied = Path(engine.library._name)
        observed.append((engine.library._handle, copied, copied.read_bytes()))
        return original(engine, *args)

    monkeypatch.setattr(native.NativeEngine, "surface", measured)
    first = sweep.sweep([sweep.Profile(12, 8)], "12x8")
    second = sweep.sweep([sweep.Profile(12, 8)], "12x8")
    assert len(observed) == 2
    assert len({earlier.library._handle, *(handle for handle, _, _ in observed)}) == 3
    assert observed[0][1] != observed[1][1]
    for result, (_, copied, actual_bytes) in zip((first, second), observed):
        assert actual_bytes == payload
        assert copied != source and not copied.exists() and not copied.parent.exists()
        rows, decisions = result
        carried = sweep.receipt(rows, decisions, provenance=result.provenance)
        assert carried["provenance"]["nativeLibrary"] == provenance.byte_identity(payload)


def test_isolated_copy_mutation_refuses_receipt_and_cleans_up(native, tmp_path, monkeypatch, capsys):
    original = native.NativeEngine.surface
    observed = []

    def mutate_copy_after_native(engine, *args):
        result = original(engine, *args)
        copied = Path(engine.library._name)
        observed.append(copied)
        replacement = copied.with_name("changed.so")
        replacement.write_bytes(copied.read_bytes())
        replacement.replace(copied)  # Never write into a live mapped inode.
        return result

    monkeypatch.setattr(native.NativeEngine, "surface", mutate_copy_after_native)
    output = tmp_path / "receipt.json"
    assert sweep.main(["--profile", "12x8", "--baseline", "12x8", "--json-out", str(output)]) == 1
    assert not output.exists()
    assert len(observed) == 1 and not observed[0].parent.exists()
    captured = capsys.readouterr()
    assert not captured.out and "isolated native library" in captured.err


def test_copy_failure_cleans_up_before_native_load(native, tmp_path, monkeypatch):
    directories = []

    def corrupt_copy(source, destination):
        directories.append(Path(destination.name).parent)
        destination.write(b"not the captured native bytes")

    monkeypatch.setattr(provenance.shutil, "copyfileobj", corrupt_copy)
    with pytest.raises(provenance.ProvenanceChanged, match="copy differs"):
        sweep.sweep([sweep.Profile(12, 8)], "12x8")
    assert len(directories) == 1 and not directories[0].exists()


def test_source_checkout_cannot_hold_native_temporary_copies(native, monkeypatch):
    monkeypatch.setattr(provenance.tempfile, "gettempdir", lambda: str(ROOT))
    with pytest.raises(sweep.NativeUnavailable, match="outside the source repository"):
        sweep.sweep([sweep.Profile(12, 8)], "12x8")


def test_native_load_failure_cleans_up_and_remains_unavailable(native, tmp_path, monkeypatch, capsys):
    observed = []

    def failed_load(path):
        observed.append(path)
        assert path.is_file()
        raise OSError("native loader rejected this copy")

    monkeypatch.setattr(native, "NativeEngine", failed_load)
    output = tmp_path / "receipt.json"
    assert sweep.main(["--profile", "12x8", "--baseline", "12x8", "--json-out", str(output)]) == 2
    assert len(observed) == 1 and not observed[0].parent.exists()
    assert not output.exists()
    captured = capsys.readouterr()
    assert not captured.out and "NATIVE_UNAVAILABLE:" in captured.err


def add_observed_helper(monkeypatch, helper):
    original = provenance.NativeRunInputs.capture.__func__

    def capture(cls, library):
        inputs = original(cls, library)
        return replace(inputs, helpers=inputs.helpers + (
            provenance.FileIdentity.capture("test-helper", helper),
        ))

    monkeypatch.setattr(provenance.NativeRunInputs, "capture", classmethod(capture))


@pytest.mark.parametrize("changed", ["library", "helper", "rgba", "cameras"])
def test_native_execution_mutation_refuses_receipt(native, tmp_path, monkeypatch, capsys, changed):
    library = tmp_path / native.library_path().name
    shutil.copyfile(native.library_path(), library)
    monkeypatch.setenv("RF_NATIVE_LIBRARY", str(library))
    helper = tmp_path / "helper.py"
    helper.write_text("before")
    add_observed_helper(monkeypatch, helper)
    original = native.NativeEngine.surface

    def mutate_after_native(engine, rgba, cameras, *args):
        result = original(engine, rgba, cameras, *args)
        if changed == "library":
            replacement = tmp_path / "replacement.so"
            replacement.write_bytes(library.read_bytes() + b"changed")
            replacement.replace(library)  # Do not modify the loaded mapping.
        elif changed == "helper":
            helper.write_text("after")
        elif changed == "rgba":
            rgba[0, 0, 0, 0] ^= 1
        else:
            cameras[0, 0] += 1
        return result

    monkeypatch.setattr(native.NativeEngine, "surface", mutate_after_native)
    output = tmp_path / "receipt.json"
    assert sweep.main(["--profile", "12x8", "--baseline", "12x8", "--json-out", str(output)]) == 1
    assert not output.exists()
    captured = capsys.readouterr()
    assert not captured.out and "changed" in captured.err


def test_helper_change_after_render_is_checked_before_publication(native, tmp_path, monkeypatch, capsys):
    helper = tmp_path / "helper.py"
    helper.write_text("before")
    add_observed_helper(monkeypatch, helper)
    original = sweep.render_markdown

    def render_then_change(*args):
        rendered = original(*args)
        helper.write_text("after")
        return rendered

    monkeypatch.setattr(sweep, "render_markdown", render_then_change)
    output = tmp_path / "receipt.json"
    assert sweep.main(["--profile", "12x8", "--baseline", "12x8", "--json-out", str(output)]) == 1
    assert not output.exists() and not capsys.readouterr().out


def test_evidence_cannot_be_reused_for_different_measurements(native):
    result = sweep.sweep([sweep.Profile(12, 8)], "12x8")
    assert isinstance(result, tuple) and len(result) == 2
    rows, decisions = result
    assert sweep.receipt(rows, decisions)["provenance"] is None
    altered = [replace(rows[0], evaluated=rows[0].evaluated + 1, rejected=rows[0].rejected + 1)]
    with pytest.raises(provenance.ProvenanceChanged, match="reported measurements"):
        sweep.receipt(altered, decisions, provenance=result.provenance)


@pytest.mark.parametrize("change", ["contents", "replacement", "deletion"])
def test_file_identity_fails_closed_on_changed_or_missing_file(tmp_path, change):
    path = tmp_path / "helper.py"
    path.write_bytes(b"original")
    identity = provenance.FileIdentity.capture("helper", path)
    if change == "contents":
        path.write_bytes(b"modified")
    elif change == "replacement":
        replacement = tmp_path / "replacement.py"
        replacement.write_bytes(b"original")
        replacement.replace(path)
    else:
        path.unlink()
    with pytest.raises(provenance.ProvenanceChanged):
        identity.verify()


def test_file_change_while_hashing_is_rejected(tmp_path, monkeypatch):
    path = tmp_path / "helper.py"
    path.write_bytes(b"before")
    original = provenance.hashlib.file_digest

    def hash_then_change(stream, algorithm):
        value = original(stream, algorithm)
        path.write_bytes(b"after")
        return value

    monkeypatch.setattr(provenance.hashlib, "file_digest", hash_then_change)
    with pytest.raises(provenance.ProvenanceChanged, match="while hashing"):
        provenance.FileIdentity.capture("helper", path)


def test_missing_git_context_is_explicitly_unavailable(tmp_path, monkeypatch):
    def unavailable(*args, **kwargs):
        raise FileNotFoundError("git unavailable")

    monkeypatch.setattr(provenance.subprocess, "run", unavailable)
    assert provenance._git_context(tmp_path) == (None, None)
