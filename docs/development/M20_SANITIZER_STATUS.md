# M20 preparation: sanitizer evidence reviewed on 19 November 2026

**Sanitizer qualification remains open.** The retained baseline CI run is
**FAIL**. The current local sanitizer runtime is **BLOCKED_ENVIRONMENT**. Neither
outcome is converted into a pass by the release build, functional assertions,
scanner-free controls, or diagnostic/export parity.

This report reviews already executed artifacts for the
[M20 preparation campaign](M20_PREPARATION_2026_11_19.md). It records no new
sanitizer execution and does not qualify a later candidate commit.

## Source-specific baseline CI

The reviewed GCC artifact belongs to
[`a373aff43a03bd736edb16707cbf5edabf5f2156`](https://github.com/cheshmakzanoon-ops/3d-scanner/commit/a373aff43a03bd736edb16707cbf5edabf5f2156),
[Verify run 40944757140, attempt 1](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40944757140).
The retained `evidence.json` reports a clean source tree, `gcc-sanitize`, and
`result: FAIL`; execution began on 29 October 2026. The reviewed failure artifact
has ID `12930592851`.

Raw `pytest.xml` and its completed accounting receipt agree on:

| Accounted outcome | Count |
|---|---:|
| Passing parent tests | 4,460 |
| Passing subtests | 362 |
| Total passing cases | 4,822 |
| Failed parent tests | 13 |
| Skipped parent tests | 28 |
| Errors | 0 |
| Total cases, including subtests | 4,863 |

There are 4,501 parent `<testcase>` elements. The additional 362 passing subtests
are explicit accounting events, not inferred passes or additional XML parent
elements. The receipt records `session_exitstatus: 1` and `completed: true`.
Older documents reporting 14 failures refer to earlier source revisions; their
counts must not replace this artifact's 13 failed identities.

### Failed identities and observed boundaries

| Test group | Cases | Evidence retained in the failing invocation |
|---|---:|---|
| `test_agent_003_jni_boundary.py::test_actual_jvm_native_boundary` | 1 | The actual JNI process ends nonzero with LeakSanitizer reporting 56,665 bytes in 1,010 allocations. It loads native code and remains a required candidate test. |
| `test_agent_083_calibration_adapter.py::test_cli_writes_new_wrapper_and_refuses_original_overwrite` | 1 | The real conversion CLI prints its conversion result, then ordinary Python shutdown reports 356,290 leaked bytes. Its nonzero exit fails the test. |
| `test_agent_100_campaign.py::test_real_merge_helper_rejects_postcheck_source_change` | 4 | The `unchanged`, `unstaged`, `staged` and `untracked` cases encounter nested Python/source-manifest shutdown failures. The intended postcheck assertions are not thereby qualified. |
| `test_native_jvm_baseline.py::test_actual_java_mapping_detector_aliases_and_missing_evidence` | 1 | The mapping-control marker is printed, followed by a JVM exit leak report of 60,451 bytes. The ordinary exit is still a failure. |
| `test_native_pytest_diagnostics.py`, `test_native_pytest_identity_budget.py`, `test_native_pytest_progress.py` | 3 | The child pytest, full-suite collection and progress-observer processes end with Python leak reports. Completed functional work does not replace successful finalization. |
| `test_optimized_host_validation.py::test_validation_survives_optimized_python` | 3 | The normal, `-O` and `-OO` processes each print 67 checks, then report shutdown leaks and exit nonzero. |

The collected tracebacks identify process-exit LeakSanitizer failures. This is
useful failure attribution, not proof that every scanner allocation is correct
or that all failures share a single cause. In particular, the actual JNI test
must continue to execute under the candidate sanitizer environment.

## Independent scanner-free controls

The same GCC run retained ordinary-exit controls with the candidate phase's
sanitizer settings. They report empty scanner-mapping snapshots and fail with:

| Control log | Leaked bytes reported | Allocations reported |
|---|---:|---:|
| `python-baseline-empty.log` | 213,822 | 4,060 |
| `python-baseline-numpy.log` | 388,384 | 7,184 |
| `jvm-baseline-empty.log` | 50,410 | 926 |

The empty Python control uses CPython 3.12.15 with `Py_DEBUG=0`; the JVM control
records Eclipse Adoptium Java 17.0.21. A mapping snapshot is evidence at its
inspection point, not a whole-process lifetime proof. These controls demonstrate
that shutdown failures can occur without a scanner load. Their leak totals are
**not allowances** and must not be subtracted from candidate results.

The run retains `ASAN_OPTIONS=halt_on_error=1:detect_leaks=1` and
`UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1`, with the selected ASan and
C++ runtime preloads for Python phases. The existing
[native regression control contract](../../tools/qa/native_regression/README.md)
requires these failures to remain blocking and candidate phases to continue.

## Local execution environment

The campaign's local GCC ASan/UBSan build completed all 147 build steps. The
attempted CTest run then encountered the same fatal message in its first four
tests and was stopped during the fifth; it is not a completed sanitizer suite:

```text
LeakSanitizer has encountered a fatal error.
LeakSanitizer does not work under ptrace (strace, gdb, etc)
```

A separately compiled empty `int main() { return 0; }` program produced the same
fatal message. This supports **BLOCKED_ENVIRONMENT** for local runtime
qualification. It does not diagnose a scanner leak, establish a clean leak
check, or justify disabling leak detection. The local build and control logs
were inspected; they were not rerun for this report.

## Required next evidence

Run the unchanged sanitizer requirements on a supported execution environment
and retain the exact candidate source, compiler/runtime identities, ordinary
process exits, controls, raw JUnit and accounting receipt. Fix demonstrated
causes and obtain a completed passing result before declaring native
qualification complete.

The existing compiler-only environment boundary removes loader injection from
build tools, not from candidate executions. The separately audited fixed
protocol fixtures are also narrower than arbitrary repository CLIs or full-suite
collection. Do not expand either boundary merely to hide these failures; see
[native matrix evidence](NATIVE_MATRIX_EVIDENCE.md) and
[`native_compile_env`](../../tools/native_process.py).

### Retained artifact identities

These hashes identify the reviewed GCC files from run `40944757140:1`; they are
not hashes of newly generated candidate results.

| Artifact within `gcc-sanitize` | SHA-256 |
|---|---|
| `pytest.xml` (1,747,279 bytes) | `e648c8f264bb10d36d835d31a5ec52542446d4dcf6850651db4fdf9a26a96077` |
| `pytest.log` (5,626,590 bytes) | `964c489b9943c4726e619bdeaa338548e07d85dd84ad24c6ba9eb1d4dcb492c8` |
| `pytest-accounting/receipt.json` (4,804,811 bytes) | `ef4bbdb0b143dfac0a9b0eb97368cb9c3a9466609b5a968f301728225014de47` |
