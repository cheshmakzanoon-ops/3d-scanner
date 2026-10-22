# Interpreter-only LSan comparison

**NOT RUN on hosted GCC13; not scanner or release-gate qualification.** This is
an additive workflow, triggered manually or by changes to its own workflow/helper
files on main only. It does not import scanner modules or modify
any required gate, security policy, dependency lock or exception. A clean debug
cell says nothing beyond its exact interpreter workload. Do not recover/enable
any older Py_DEBUG workflow: its dependency provenance was lost.

## Experiment

The workflow targets ubuntu-24.04 x86_64 and official apt GCC13. Both interpreters
use the **same** official CPython 3.12.15 archive; out-of-tree configure differs
only by `--with-pydebug`. The extracted source is made read-only before building.
Neither Python build is itself ASan-instrumented. Both preload the same GCC13
ASan and libstdc++ runtimes, with `PYTHONMALLOC=malloc` and leak detection enabled.
The planted C executable/shared helper are ASan-instrumented.

Order: standalone empty/planted prerequisite controls, source/wheel acquisition,
release then debug build, and each arm's independent empty (`pass`), NumPy-only
(`import numpy`), planted ctypes-helper and metadata/mappings processes. All
processes end by ordinary return/finalization. Metadata imports never pollute
empty/NumPy-only cells. A planted control is valid only with the ordinary LSan
leak report, exit 1, and **a direct 1,237-byte/one-object allocation block naming
`planted_leak`**. Nonzero exit alone proves nothing. Fatal runtime errors,
timeouts, truncated logs, missing metadata and missing positive-control detection
invalidate the comparison. Primary-cell leak reports are observations, not a
failure to preserve leak checking. A completed workflow is never a scanner pass.

Python planted failures are checked after both arms, so the other arm can still
supply evidence. Acquisition/build/metadata failures stop subsequent work and
leave a failure marker; no inference may be made for unexecuted cells.

## Pins and provenance

- CPython source: https://www.python.org/ftp/python/3.12.15/Python-3.12.15.tar.xz
- Official release checksum evidence: https://www.python.org/downloads/release/python-31215/
- SHA256: `c2c4321961fab0fb999d66e0cecf521c2ab3994c7992873ea99e306c1094fd5a`
- NumPy 2.2.6 CPython312 manylinux x86_64 wheel SHA256:
  `fd83c01228a688733f1ded5201c678f0c53ecc1006ffbc404db9f7a899ac6249`
  (same approved pin in `tools/dependencies/qa-requirements.lock`). Official
  `https://pypi.org/pypi/numpy/2.2.6/json` resolves only that exact hash, wheel ABI
  and architecture, with artifact origin restricted to files.pythonhosted.org.
  The actual downloaded bytes are hash-checked before extraction.
- Py_DEBUG background: https://docs.python.org/3.12/using/configure.html#python-debug-build

The wheel is unpacked once into a shared external directory (no pip resolution,
source rebuild or dependency substitution), and both metadata probes must report
NumPy 2.2.6 from that directory. The interpreter patch version and Py_DEBUG flag
are checked, not inferred from executable names.

## Deliberate differences from current required CI baseline

This experiment uses two source-built CPython **3.12.15** interpreters, not the
moving setup-python `3.12` distribution; selects GCC13 explicitly; loads only
NumPy rather than the complete QA resolution or scanner; runs sequentially; and
compares release against Py_DEBUG. It cannot establish causality relative to a
different Python patch/compiler or a scanner-loaded workload.

An environment allowlist replaces inherited environment settings for **both**
arms. Any inherited LD_PRELOAD, LD_AUDIT, ASAN_OPTIONS, LSAN_OPTIONS or
UBSAN_OPTIONS is rejected. Python/build/loader/pip overrides are discarded;
PATH is `/usr/bin:/bin`, HOME is a fresh owned directory, locale is C.UTF-8,
user site and unsafe current-directory path insertion are disabled (`-s -P`,
PYTHONNOUSERSITE/PYTHONSAFEPATH), bytecode writes disabled, and OpenBLAS/OMP/MKL
thread counts all pinned to one. The only workload PYTHONPATH is the shared
wheel directory. These isolation differences are intentional, recorded, and
are not changes to the required scanner environment.

## Bounds, artifacts and local tests

- Job 30 minutes; orchestrator 22 minutes, leaving artifact-upload headroom after
  bounded apt provisioning. Both configure+make together have at most 600 seconds
  per arm, `make -j2`; ordinary probes have 30 seconds each.
- Every external process launched by the helper starts an owned session. The
  helper kills the session group on success, failure, timeout, output cap or
  handled SIGTERM/SIGINT, including descendants left after a leader exits.
  Signal handlers record cancellation without raising inside the Popen ownership
  window. Linux waitid(WNOWAIT) observes exit without reaping; group signaling
  finishes before the first reap, preventing leader PID/group reuse races.
  Cleanup errors preserve per-command JSON and invalidate/stop the experiment.
  Uncatchable SIGKILL/runner destruction cannot guarantee evidence upload.
- Combined stdout/stderr per command capped at 4 MiB. Overflow kills the group
  and marks the command invalid; config/build metadata copies are capped too and
  truncation invalidates the experiment. Partial logs/exit/timing metadata and
  config.log/config.status/Makefile/pyconfig.h survive build failure.
- Evidence includes commands, exact sanitized environments, OS/runner/package
  versions, compiler/runtime/control/Python hashes, source hash and wheel origin,
  interpreter/configuration/NumPy metadata, process mappings, exit codes, fatal/
  timeout/truncation flags, parsed LSan bytes/allocations and side-by-side table.
- Each run requires a fresh output directory; no old artifact reuse. Work files
  remain in owned runner-temp storage until runner teardown. No external files
  are deleted and no artifacts are published by the helper itself.

Run **synthetic tests only** locally:

```sh
python3 -m unittest discover -s tools/qa/python_lsan_comparison -p 'test_*.py' -v
```

Tests explicitly fake process behavior/reports and complete orchestration. They
check command/env symmetry, exact workload bodies, classification, failures,
output/time bounds, deterministic spawn-window SIGTERM injection, non-reaping
observation/group-signal/reap ordering, cleanup-error evidence, and ordinary
child-process cleanup. They do
not establish that LSan works. Local GCC14 LSan is known BLOCKED by ptrace; do not
bypass that restriction or build both Pythons locally. The hosted workflow must
be reviewed/authorized for publication; its narrow push trigger runs the experiment
when these diagnostic files change, independently of existing required gates.
