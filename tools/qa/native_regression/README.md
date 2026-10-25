# Current-source native numerical gate

Run from the selected checkout with Python requirements from `tools/requirements*.txt`,
CMake >=3.22, Ninja, GCC and host Clang/compiler-rt available:

```bash
python tools/qa/native_regression/run.py --output /tmp/rf-native-evidence
```

`--gcc` and `--clang` accept exact compiler paths. The default four configurations
are GCC/Clang Release and GCC/Clang Debug ASan+UBSan, isolated in the evidence
folder. Each compiles the actual native library, executes all registered
CTests, ten seeded analytic numerical scenes with repeated exact determinism,
the complete Python suite, synthetic photograph joint BA, and all five existing
gated stereo/coverage/dense/surface/texture benchmarks. No old report is read as
evidence. Real Middlebury stereo remains distinct from synthetic 360/plane data.
Those commands can acquire the licensed skimage dataset via its normal verified
cache; network access is a development dependency, never an app requirement.

Each process has a 600-second default deadline (configurable 1..3600), 32-MiB log
limit and one native build job; BLAS/OpenMP threads are limited to one.
`--pytest-timeout` explicitly overrides only the full single-process pytest phase
with an integer 1..1800-second allowance; when omitted, pytest inherits `--timeout`.
CI opts only sanitizer profiles into 1800 seconds; release profiles and other
ordinary commands retain 600 seconds, and control limits remain unchanged.
This is a test-resource budget increase, not a performance optimization or a pass:
up to 1200 extra seconds per sanitizer profile (40 additional runner-minutes across
two profiles). The outer CI job ceiling remains 50 minutes; completion is not
guaranteed. No app/HOST admission, version-probe or security deadline changes.
Receipts record the ordinary-command and effective pytest budgets in
`resource_limits`, and each command records its actual `timeout_seconds`. Timeouts
kill the entire process group. ASan shadow memory prevents meaningful virtual
address limits; CTest and process deadlines bound this trusted regression suite.
ASan/UBSan halt on errors, and leak detection stays enabled. Sanitized Python uses
its host ASan and libstdc++ runtime preloads and PYTHONMALLOC=malloc; CTest remains independent.
Both Python preloads must exist before any Python native phase is launched; a
missing runtime records NOT_RUN and fails the configuration. Compiler discovery,
build tools and direct C++ fixture compilations remove LD_PRELOAD/LD_AUDIT only
from their own child environments: these injections instrument the compiler, not
its output. Candidate-linked C++ fixtures use the selected matrix compiler and
explicit matching ASan/UBSan/float-cast-overflow compile/link flags. Clang fixtures
link its shared ASan runtime, matching the Python preload rather than introducing
a second static ASan runtime. Native test executions retain sanitizer runtime injection and strict
leak/error options. The host-tool boundary does not qualify or waive CPython,
NumPy, JVM or other child-process shutdown leaks.
No leak suppressions or sanitizer retry-to-pass policy is installed. CPython/NumPy
process-exit leak reports remain failures and require a separately retained minimal
third-party reproduction; the runner never turns them into passing receipts.
Before each sanitized Python phase group, two independent ordinary-exit controls
run the same interpreter with an empty workload and with only NumPy imported.
Each uses the exact candidate-phase environment and at most 30 seconds (or the
configured command deadline if shorter). Logs retain Python executable/build
identity, Py_DEBUG status, NumPy module/extension provenance, sanitizer options
and loaded-library evidence. Neither control imports or loads the scanner.
Any control failure remains a failed command/configuration; existing numerical,
pytest and benchmark phases still run. No early leak check replaces finalization.
A scanner-free JVM startup/ordinary-exit control then resolves `javac` from the
same PATH and uses that resolved JDK's `bin/java`, matching the actual JNI test.
Compilation uses the existing native compile environment; JVM execution preserves
all sanitizer/preload options and the JNI helper's candidate-directory search
precedence. Separate compile/run logs retain exits and hashes, with the existing
32 MiB command-log limit and at most 60 seconds per command (or the configured
deadline if shorter). Source, compiler, Java executable, JDK release and generated
class are hashed. The Java main reads `/proc/self/maps` and fails if the selected
candidate device/inode (including renamed/deleted hardlink aliases) or known
scanner/JNI library names are mapped; missing or malformed map evidence
also fails. This is only a mapping snapshot, not proof about the whole process
lifetime. It is not zero-allocation: Java startup and diagnostic reads allocate.
It returns ordinarily without a native load, shutdown hook, GC request or early
leak check. Baseline failure or missing JDK remains FAIL/NOT_RUN and does not skip
any candidate phase. Leak totals are never subtracted or accepted as allowances.
The Python suite emits an immediate, explicitly flushed copy of its first failed
setup, call or teardown report to the existing bounded command log. This copy is
limited to 64 KiB total across traceback and labeled captured-output sections.
Oversized sections retain bounded head/tail snippets, including trailing leak
summaries, with explicit truncation markers; the underlying report is unchanged.
Tests continue in
their original order, so this is not fail-fast, a retry or a reduced suite. A
later timeout remains FAIL/TIMEOUT with the original deadline, even when the
usual end-of-session traceback/JUnit output was never produced. The observer
does not classify the cause or excuse CPython, NumPy or scanner failures.
Use a fresh evidence directory for each invocation; old receipts are immutable.

`evidence.json` uses `realityforge.native-regression.v1`: source SHA and dirty flag,
UTC time, compiler version/executable hash, binary hashes, exact argv/cwd,
exit status, time, log hash, per-seed metrics, artifact hashes and parity results.
Keep the whole output directory for review. A failure exits nonzero; missing
compiler reports NOT_RUN and also exits nonzero. A matrix worker can use
`--configs gcc-release` etc.; combined four-config execution additionally compares
all numerical results against the first successful config.

Analytic exact observations retain the existing 1e-5 rotation/position contract;
noisy/outlier scenes retain .035 rotation and existing retained-point thresholds.
Compiler parity uses rtol=1e-5/atol=1e-6 (the exact geometry/float32 export contracts),
with scene IDs/order unchanged. Nonfinite values fail. This measures numerical
regression, not physical scan quality. Deliberately invalid numerical fixtures and
subprocess failures are exercised by `tests/test_native_regression_harness.py`.

### Interrupted pytest timing diagnostics

The runner loads `pytest_progress` to append `RF_PYTEST_PROGRESS` JSON records
to the existing `pytest.log`, retained by the native failure-artifact upload.
Every setup/call/teardown start and end is flushed immediately; pair records by
`phase_id` and compare `duration_s` or session-relative monotonic `elapsed_s`.
An unmatched start identifies interrupted/in-flight work, not its outcome. End
records do **not** mean PASS; only the existing exit status, JUnit and required
accounting validation determine acceptance. No timeout or sanitizer setting changes.

Records expose only allowlisted relative test paths/names (otherwise `<redacted>`),
never parameter values, captured output or absolute paths. Parameter cases retain
distinct phase IDs even when their displayed names are identical. Added output is
bounded to 4 MiB and 100,000 phase records plus at most one `limit` marker; that
marker means diagnostics are incomplete. Output errors disable this optional
observer without replacing test failures. This is process-kill diagnostic evidence,
not a power-loss durability guarantee or a replacement for sanitizer qualification.
