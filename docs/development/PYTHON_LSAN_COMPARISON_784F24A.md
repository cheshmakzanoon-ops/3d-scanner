# Hosted comparison: interpretable result, NOT scanner qualification

Run: https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39875293783

Commit `784f24ac195f1ff533bbb69bae92e273d0fd3c0b`, attempt 1, job 120159314018.
Run completed SUCCESS at 2026-10-23T00:03:51Z. Synthetic suite: 22 passed.
Artifact 12468401446, 85 files, 314442 bytes. Downloaded ZIP SHA256 matches
GitHub's uploaded digest: `6a3c3f979165e702127682b2ab14719d92c684c4410159d862dee2a573f5639e`.

## Actual primary results

| Ordinary-final-exit workload | Release exit / bytes / allocations | Py_DEBUG exit / bytes / allocations |
|---|---|---|
| Empty (`pass`) | 1 / 33,082 / 618 | 0 / no report / no report |
| NumPy-only (`import numpy`) | 1 / 248,925 / 4,552 | 1 / 42,515 / 560 |

Thus Py_DEBUG eliminated the observed empty-workload report in this exact run,
but **did not make NumPy-only exit leak-free**. The empty release report's sole
allocation block names `PyUnicode_New`. This is consistent with the proposed
interpreter-cleanup explanation, not proof of sole causality or scanner behavior.

## Positive controls validated against raw logs

- Standalone native empty: exit0, empty log.
- Standalone planted: exit1; direct 1,237 bytes / one object; `planted_leak` frame.
- Release Python planted: exit1; direct 1,237 bytes / one object with
  `planted_leak` plus pre-existing other reports (29,498 bytes / 528 total).
- Debug Python planted: exit1; exactly 1,237 bytes / one allocation and
  `planted_leak` frame. Clean empty result was not caused by disabled detection.

All raw allocation-block sums match the reported LSan summaries and cells.json.
No command recorded timeout, truncation, cancellation, launch or cleanup error;
all non-cell commands exited0. No FAILURE or truncation marker exists. Full logs
and exact commands show ordinary exit, no early leak check, manual finalization,
os._exit or suppression. No scanner modules were loaded.

## Source, configuration and provenance checks

Both independent out-of-tree builds completed successfully from the same official
CPython3.12.15 archive, SHA256
`c2c4321961fab0fb999d66e0cecf521c2ab3994c7992873ea99e306c1094fd5a`.
The shared extracted source was made read-only before builds. Configure arguments
are `--with-ensurepip=no` vs that plus `--with-pydebug`; both record `CC=gcc-13`.
pyconfig.h and runtime metadata agree on Py_DEBUG0/1; Py_TRACE_REFS is undefined
in both. No source patch or alternate allocator configuration was introduced.

Release configure/make took 22.794/80.807 seconds; debug 23.058/46.235 seconds,
well within each combined 600-second build budget.

Both metadata report NumPy2.2.6 imported from the same external wheel directory.
Wheel: `numpy-2.2.6-cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64.whl`,
SHA256 `fd83c01228a688733f1ded5201c678f0c53ecc1006ffbc404db9f7a899ac6249`.
Official PyPI metadata/url retained. The downloaded source/wheel archives are not
in the evidence ZIP; acquisition/hash validation is evidenced by the recorded
helper, successful commands, source pin and wheel-origin metadata, not independently
rehashable archive bytes in this review.

Executable SHA256:
- release: `01a8cc858161ff6b093ab5c5076ba9ca5e497c48e5d2debbb77197e355e5548a`
- debug: `0a867291ed2718b6fff208f93189fd349aeffd30a3b8d9c706302c7931a70027`

## Important runtime-version precision

Compiler: GCC13.3.0, Ubuntu package `13.3.0-6ubuntu2~24.04.1`.
Runner image: ubuntu24 `20261012.334.1`.

The GCC13 lookup resolved the system libasan8 and libstdc++6 packages, which are
**14.2.0-4ubuntu2~24.04.1**, not version13 runtime packages. Describe this as
GCC13-built Python with its selected Ubuntu libasan8, not a pure GCC13 runtime.
This does not confound the two arms: both used the identical paths/hashes, and
both metadata /proc mappings show those exact files:

- `/usr/lib/x86_64-linux-gnu/libasan.so.8.0.0`:
  `8d5845bdf056b5db4cfb9fff40ad5eeea8f6da05a8ca6f5935a7d1c138ed2558`
- `/usr/lib/x86_64-linux-gnu/libstdc++.so.6.0.33`:
  `1fd75fe70354a416d75aef22bcae68c47bd25d20e2d0568c30b1a9838cf62f11`

Both use the documented allowlisted environment, PYTHONMALLOC=malloc,
detect_leaks=1, identical preload, disabled user-site/current-directory additions,
one-thread OpenBLAS/OMP/MKL and the same external NumPy path.

## Residual NumPy observation and next decision

Debug NumPy direct-allocation traces include NumPy `_multiarray_umath` frames
`PyUFunc_AddLoopFromSpec_int`, `PyUFunc_AddPromoter`, `init_promoter`, and
`init_string_ufuncs`, as well as Python allocation/import paths. They are useful
localization evidence, not proof that every retained object has one specific
upstream owner or a safe suppression. Nothing justifies reclassifying scanner
sanitizer failures as passes.

No recoverable protocol/build failure was found and no retry is needed for this
run's stated experiment. If more development is desired, first investigate the
remaining NumPy ordinary-exit reports and any scanner-loaded behavior separately;
do not simply switch required gates to Py_DEBUG and claim success. Further
experiments require their own provenance and review; this result does not
authorize changing required gates.
