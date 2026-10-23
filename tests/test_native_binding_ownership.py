"""Actual ctypes ownership regression, NOT interpreter-cleanliness evidence.

Each spy delegates to the actual native function or rf_free. Decode failures are
explicit injections after real native allocation, not claims of corrupt native
UTF-8/JSON. Decode KeyboardInterrupt is not native cancellation coverage.
"""
from pathlib import Path
import importlib.util
import json
import sys

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import native_api


def fixture_module(name):
    spec = importlib.util.spec_from_file_location('ownership_' + name, ROOT / 'tests' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope='module')
def workloads(tmp_path_factory):
    rows, *_ = fixture_module('test_pipeline').observations()
    tracks, *_ = fixture_module('test_multiview').synthetic()
    cams, points, observations = fixture_module('test_bundle_adjustment').fixture_rows()
    # Capture the existing benchmark's actual call inputs before native execution;
    # do not commit photographs or substitute truth-derived geometry.
    from tools.qa.native_cabi_replay.prepare import Capture, Captured, captured_arrays
    import benchmark_texture_middlebury
    output = tmp_path_factory.mktemp('binding-photographic-fixture')
    capture = Capture()
    with pytest.MonkeyPatch.context() as patcher:
        patcher.setattr(benchmark_texture_middlebury, 'NativeEngine', lambda: capture)
        with pytest.raises(Captured):
            benchmark_texture_middlebury.run(output_dir=output / 'preparation-only')
    _, (depth, photos, cameras) = captured_arrays(2, capture.call, output)
    return {
        'reconstruct': lambda engine, bad: engine.reconstruct(rows, threshold=-1 if bad else .002),
        'register_camera': lambda engine, bad: engine.register_camera(tracks, threshold=-1 if bad else .003),
        'bundle_adjust': lambda engine, bad: engine.bundle_adjust(cams, points, observations, threshold=-1 if bad else .025, iterations=3),
        'dense': lambda engine, bad: engine.dense(depth, cameras, -1 if bad else 6, 80, 64, 3),
        'surface': lambda engine, bad: engine.surface(depth, cameras, -1 if bad else 6, 80, 64, 3),
        'textured_surface': lambda engine, bad: engine.textured_surface(depth, photos, cameras, -1 if bad else 6, 80, 64, 3),
    }


@pytest.mark.parametrize('method', ['reconstruct', 'register_camera', 'bundle_adjust', 'dense', 'surface', 'textured_surface'])
@pytest.mark.parametrize('case', ['success', 'native_error', 'json_decode_error', 'utf8_decode_error', 'decode_keyboard_interrupt'])
def test_real_response_freed_exactly_once(workloads, monkeypatch, method, case):
    """Every independent wrapper releases its actual response despite decode failure.

    The error case changes numeric settings to elicit real native error JSON.
    Malformed JSON/UTF-8 and KeyboardInterrupt are explicitly injected on the
    Python decoding side, after the native call has returned its real pointer.
    They do not claim native corruption, signal-during-call safety or native
    cancellation coverage. The free spy delegates exactly once, with no extra
    normal-path teardown free. Failure-only cleanup runs after ownership assertions
    and does not alter observed frees or turn a failed ownership check into a pass.
    """
    engine = native_api.NativeEngine()
    symbol = 'rf_' + method + '_json'
    real_call = getattr(engine.library, symbol)
    real_free = engine.library.rf_free
    returned, released = [], []

    def tracked_call(*args):
        pointer = real_call(*args)
        returned.append(pointer)
        return pointer

    def tracked_free(pointer):
        assert pointer in returned, "Wrapper attempted to free an unowned pointer"
        assert pointer not in released, "Wrapper attempted to double free its response"
        real_free(pointer)
        released.append(pointer)

    monkeypatch.setattr(engine.library, symbol, tracked_call)
    monkeypatch.setattr(engine.library, 'rf_free', tracked_free)
    expected = {'native_error': ValueError, 'json_decode_error': json.JSONDecodeError,
                'utf8_decode_error': UnicodeDecodeError, 'decode_keyboard_interrupt': KeyboardInterrupt}.get(case)
    if case in ('json_decode_error', 'decode_keyboard_interrupt'):
        def injected_decode(*_args, **_kwargs):
            if case == 'json_decode_error':
                raise json.JSONDecodeError('injected ownership probe', '', 0)
            raise KeyboardInterrupt('injected during decoding, not native cancellation')
        monkeypatch.setattr(native_api.json, 'loads', injected_decode)
    elif case == 'utf8_decode_error':
        monkeypatch.setattr(native_api.ctypes, 'string_at', lambda _pointer: b'\xff')
    try:
        if expected is None:
            result = workloads[method](engine, False)
            assert isinstance(result, dict) and 'error' not in result
        else:
            with pytest.raises(expected):
                workloads[method](engine, case == 'native_error')
        assert len(returned) == 1 and returned[0], 'Expected actual non-null native allocation'
        assert released == returned, 'Same actual native response must be freed exactly once'
    finally:
        # A missing-free regression still fails above. Release known outstanding
        # test-owned allocations only after that assertion, without recording the
        # cleanup as a wrapper free or risking a duplicate free.
        for pointer in set(returned) - set(released):
            if pointer:
                real_free(pointer)
