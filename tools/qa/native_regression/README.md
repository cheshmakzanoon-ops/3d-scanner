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
limit and one native build job; BLAS/OpenMP threads are limited to one. Timeouts
kill the entire process group. ASan shadow memory prevents meaningful virtual
address limits; CTest and process deadlines bound this trusted regression suite.
ASan/UBSan halt on errors, and leak detection stays enabled. Sanitized Python uses
its host ASan and libstdc++ runtime preloads and PYTHONMALLOC=malloc; CTest remains independent.
Both Python preloads must exist before any Python native phase is launched; a
missing runtime records NOT_RUN and fails the configuration. Compiler discovery,
build tools and direct C++ fixture compilations remove LD_PRELOAD/LD_AUDIT only
from their own child environments: these injections instrument the compiler, not
its output. Native test executions retain sanitizer runtime injection and strict
leak/error options. The host-tool boundary does not qualify or waive CPython,
NumPy, JVM or other child-process shutdown leaks.
No leak suppressions or sanitizer retry-to-pass policy is installed. CPython/NumPy
process-exit leak reports remain failures and require a separately retained minimal
third-party reproduction; the runner never turns them into passing receipts.
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
