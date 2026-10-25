"""Opt-in C++ exact-rational QA; no production mesh or Android qualification.

Set RF_EXACT_PLANAR_OWNER_CLI to the explicitly built host QA executable.
The independent intersection/inclusion-exclusion audit is shared with the Python
reference suite; exact face comparison additionally checks native provenance.
"""
import importlib.util
import os
from pathlib import Path
import random
import subprocess
from fractions import Fraction as F

import pytest

_SPEC = importlib.util.spec_from_file_location(
    "_native_planar_oracle", Path(__file__).resolve().parents[3] / "tests/test_coherent_planar_owner_reference.py"
)
oracle = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(oracle)
CLI = os.environ.get("RF_EXACT_PLANAR_OWNER_CLI")
if not CLI:
    raise RuntimeError("Explicit QA invocation requires RF_EXACT_PLANAR_OWNER_CLI; no silent skip")
DEFAULTS = [32, 512, 2048, 4096, 200000, 128, 8192, 64 * 1024 * 1024]


def native(src, limits=None, cancel=0):
    words = [str(len(src)), *map(str, DEFAULTS if limits is None else limits), str(cancel)]
    for sid, triangle in src:
        words.append(sid)
        for point in triangle:
            for x in point:
                x = F(x)
                words.extend((str(x.numerator), str(x.denominator)))
    result = subprocess.run([CLI], input=" ".join(words), text=True,
                            capture_output=True, check=True, timeout=30)
    lines = result.stdout.splitlines()
    status, count, steps, discarded = lines[0].split()
    if status == "UNCHANGED":
        assert len(lines) == 1, "refusal must not publish partial faces"
        return {"status": status, "faces": [], "reason": int(count), "steps": int(steps), "discarded_faces": int(discarded)}
    assert status == "EXACT_QA_ONLY" and len(lines) == int(count) + 1
    faces = []
    for line in lines[1:]:
        owner, *values = line.split()
        assert len(values) == 36
        q = [F(int(values[i]), int(values[i + 1])) for i in range(0, 36, 2)]
        faces.append({"owner": owner, "positions": tuple(tuple(q[i:i + 3]) for i in (0, 3, 6)),
                      "barycentrics": tuple(tuple(q[i:i + 3]) for i in (9, 12, 15)),
                      "status": "INTERPOLATED"})
    return {"status": "EXACT_REFERENCE_ONLY", "faces": faces,
            "owners": {sid: tuple(tuple(F(x) for x in p) for p in t) for sid, t in src}, "steps": int(steps)}


def compare(src, audit=True):
    expected = oracle.reconcile(src)
    actual = native(src)
    assert actual["status"] == expected["status"], actual
    assert actual["faces"] == expected["faces"]
    if audit:
        oracle.audit(src, actual)
    return actual


@pytest.mark.parametrize("offset", [F(0), F(1, 100), 0.01, F(1, 3), F(1), F(2), F(1, 2**60)])
def test_exact_overlap_touch_disjoint_sliver(offset):
    src = oracle.square("a") + oracle.square("b", offset)
    result = compare(src)
    assert result["faces"] == native(list(reversed(src)))["faces"]


def test_hole_nonoverlap_and_tjunction():
    src = (oracle.rectangle("a", 0, 0, 3, 1) + oracle.rectangle("b", 0, 2, 3, 3)
           + oracle.rectangle("c", 0, 1, 1, 2) + oracle.rectangle("d", 2, 1, 3, 2))
    result = compare(src)
    edges = oracle.audit(src, result)
    graph = {}
    for (a, b), count in edges.items():
        if count == 1:
            graph.setdefault(a, set()).add(b)
            graph.setdefault(b, set()).add(a)
    assert all(len(neighbors) == 2 for neighbors in graph.values())
    loops, unseen = 0, set(graph)
    while unseen:
        loops += 1
        todo = [unseen.pop()]
        while todo:
            for p in graph[todo.pop()]:
                if p in unseen:
                    unseen.remove(p)
                    todo.append(p)
    assert loops == 2
    compare(oracle.square("a") + [("b", ((F(1), F(1, 2), F(5)),
                                       (F(2), F(0), F(5)), (F(2), F(1), F(5))))])


@pytest.mark.parametrize("axis", range(3))
@pytest.mark.parametrize("reverse", [False, True])
def test_oblique_winding_corner_order_attributes(axis, reverse):
    src = []
    for sid, triangle in oracle.square("a") + oracle.square("b", F(1, 3)):
        transformed = [(p[0], p[1], 2 * p[0] - 3 * p[1] + F(7)) for p in triangle]
        transformed = [p[axis:] + p[:axis] for p in transformed]
        src.append((sid, tuple(reversed(transformed)) if reverse else tuple(transformed)))
    result = compare(src, audit=False)
    for face in result["faces"]:
        owner = dict(src)[face["owner"]]
        attributes = [3*p[0] - 2*p[1] + 7*p[2] for p in owner]
        for p, w in zip(face["positions"], face["barycentrics"]):
            assert min(w) >= 0 and sum(w) == 1
            assert p == tuple(sum(w[i]*owner[i][k] for i in range(3)) for k in range(3))
            assert sum(w[i]*attributes[i] for i in range(3)) == 3*p[0]-2*p[1]+7*p[2]


@pytest.mark.parametrize("seed", range(40))
def test_deterministic_random_union(seed):
    rng = random.Random(seed)
    src = []
    for i in range(2):
        src += oracle.square(chr(97+i), F(rng.randrange(-3, 4), 3),
                             F(rng.randrange(-3, 4), 3), F(rng.randrange(1, 4), 3))
    compare(src)


@pytest.mark.parametrize("mode", ["fold", "one_ulp", "layer", "opposite", "degenerate", "duplicate_id"])
def test_unsupported_geometry_atomically_refuses(mode):
    import math
    src = oracle.square("a")
    sid, t = oracle.square("b")[0]
    t = list(t)
    if mode == "opposite":
        t.reverse()
    elif mode == "degenerate":
        t[1] = t[0]
    elif mode == "duplicate_id":
        sid = "a0"
    elif mode == "layer":
        t = [(p[0], p[1], p[2]+F(1, 10000)) for p in t]
    else:
        t[0] = (t[0][0], t[0][1], math.nextafter(5., 6.) if mode == "one_ulp" else F(6))
    assert native(src + [(sid, t)])["status"] == "UNCHANGED"


@pytest.mark.parametrize("index,value", [(0,1), (1,1), (2,1), (3,2), (4,20), (5,3), (6,1), (7,1), (4,0), (3,4097)])
def test_all_budgets_no_partial(index, value):
    limits = list(DEFAULTS)
    limits[index] = value
    result = native(oracle.square("a") + oracle.square("b", F(1, 3)), limits)
    assert result["status"] == "UNCHANGED" and result["faces"] == []


@pytest.mark.parametrize("cancel", [1, 2, 12, 40])
def test_cancellation_discards_work(cancel):
    result = native(oracle.square("a") + oracle.square("b", F(1, 3)), cancel=cancel)
    assert result["status"] == "UNCHANGED" and result["reason"] == 8


def test_checked_intermediate_overflow():
    # Coordinates fit4096bits; squared normal/orientation does not. Python can
    # normalize wider temporaries; native conservatively discards the patch.
    scale = F(2**1100)
    src = [(sid, tuple(tuple(x*scale for x in p) for p in t)) for sid, t in oracle.square("a")]
    result = native(src)
    assert result["status"] == "UNCHANGED" and result["reason"] == 4


@pytest.mark.parametrize("payload", ["", "garbage", "33", "1 " + "1 "*10, "-1"])
def test_cli_malformed_transport(payload):
    result = subprocess.run([CLI], input=payload, text=True, capture_output=True, timeout=30, check=True)
    assert result.stdout == "UNCHANGED 1 0 0\n"


def test_exact_binary64_decoder_random_bits():
    import math
    import struct
    rng = random.Random(922)
    bits = [0, 1, 1 << 63, (1 << 52) - 1, 1 << 52,
            0x7fefffffffffffff, 0x7ff0000000000000, 0x7ff0000000000001]
    bits += [rng.getrandbits(64) for _ in range(512)]
    result = subprocess.run([CLI, "--decode-binary64"],
                            input=" ".join(map(str, [len(bits), *bits])),
                            text=True, capture_output=True, check=True, timeout=30)
    lines = result.stdout.splitlines()
    assert len(lines) == len(bits)
    for pattern, line in zip(bits, lines):
        value = struct.unpack(">d", pattern.to_bytes(8, "big"))[0]
        if not math.isfinite(value):
            assert line == "NONFINITE"
        else:
            n, d = map(int, line.split())
            assert F(n, d) == F(value)
            assert (n, d) == (F(value).numerator, F(value).denominator)


def test_verified_late_cancellation_discards_faces():
    src = oracle.square("a") + oracle.square("b", F(1, 3))
    completed = native(src)
    assert completed["status"] == "EXACT_REFERENCE_ONLY"
    result = native(src, cancel=completed["steps"] // 64 + 1)
    assert result["status"] == "UNCHANGED" and result["reason"] == 8
    assert result["discarded_faces"] > 0


def test_late_output_overflow_discards_completed_faces():
    # Earlier unit-square fragments are built first. This tiny disjoint source
    # passes source-plane checks but exceeds checked intermediates during fans.
    epsilon = F(1, 2**1023)
    src = oracle.square("a") + [("b", ((F(2), F(0), F(5)),
                                     (F(2)+epsilon, F(0), F(5)),
                                     (F(2), epsilon, F(5))))]
    result = native(src)
    assert result["status"] == "UNCHANGED" and result["reason"] == 4
    assert result["discarded_faces"] > 0
