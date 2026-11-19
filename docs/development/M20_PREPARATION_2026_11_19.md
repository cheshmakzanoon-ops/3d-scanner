# M20 preparation — 19 November 2026

## Scope and status

The next named milestone is **M20: physical-device benchmark and topology
evidence**. This change prepares the software and evidence path for that work.
It does not complete M20, improve the measured geometry, qualify a handset, or
advance the `0.19.0-m19` engineering-preview version.

The campaign started from
[`a373aff43a03bd736edb16707cbf5edabf5f2156`](https://github.com/cheshmakzanoon-ops/3d-scanner/commit/a373aff43a03bd736edb16707cbf5edabf5f2156).
The owner's new instruction resumes development after the historical October
pause and requests 60 agent assignments in ten waves. The assignment record is
maintained with this handoff; an assignment is a bounded contribution or review,
not an independent claim that a product milestone passed.

## Implemented preparation

- Profile promotion requires increased supported normals and retained fusions,
  no increase in boundary edges, intersections or nonmanifold edges, and complete
  intersection checks for both the candidate and baseline. Known boundary or
  nonmanifold regressions remain a rejection even when intersection evidence is
  incomplete. An incomplete intersection count is a lower bound, not a complete
  measurement.
- Surface loss diagnostic schema 4 attributes the existing bounded topology
  passes. Local rollback, fallback rollback and retained groups are distinct.
  Branching, direction conflict, disconnected-link and endpoint-count witnesses
  can overlap; their sum is not a unique-group count.
- Report readers validate dense coverage, population partitions, retained-fusion
  accounting and the pass state machine. They accept the actual saved Android
  `densePreview`/`meshPreview` envelopes as well as native result objects.
  Unavailable evidence remains null or unknown, rather than becoming zero.
- Receipt files use exclusive publication and preserve existing destinations.
  Loss receipts identify the exact input bytes. Native sweep receipts identify
  the selected library, fixture buffers and helper sources without inferring a
  library build attestation from its filename or current checkout.
  Unique verified library copies prevent an older loaded library from being
  mistaken for replacement bytes at the same pathname. Repeated bounded sweeps
  should run as separate CLI processes because native mappings may live until
  interpreter exit. Native errors and excessively nested JSON fail cleanly.
- A reusable parity runner executes baseline and candidate libraries in separate
  ordinary-exit processes. It compares every non-diagnostic result field and the
  actual GLB/PLY bytes on three bounded synthetic fixtures. Its output must be a
  new directory outside the checkout.
- The bounded native fuzz workflow now writes evidence under `RUNNER_TEMP`.
  Writing under the checkout previously changed its own source-integrity
  snapshot and aborted qualification before fuzz execution. Source-integrity
  checks remain required.

See [diagnostic semantics](DENSE_SURFACE_LOSS_DIAGNOSTICS.md),
[report usage](DENSE_PHOTOMETRIC_LOSS.md), and
[profile interpretation](DENSE_PROFILE_SWEEP.md).

## Measured synthetic mechanism

The fixed `104 × 76`, three-camera, seeded-plane fixture is a mechanism test,
not a physical object or an installed Android journey. Its `56x3` profile still
has **2,087 vertices, 3,852 triangles, 324 boundary edges, 4,464 reported
intersections, zero nonmanifold edges and zero retained fusions**.

The new attribution explains the zero retained fusion:

| Quantity | Observed count |
|---|---:|
| Proposed weld groups | 583 |
| Groups rolled back locally on the first pass | 569 |
| Remaining groups rolled back by the fallback | 14 |
| Restored source vertices | 1,195 |
| Retained weld groups | 0 |
| First-pass branching witnesses | 523 |
| First-pass direction-conflict witnesses | 523 |
| First-pass disconnected-link witnesses | 48 |
| First-pass invalid-endpoint-count witnesses | 0 |

These witnesses describe why existing topology admission restores source
vertices. They do not justify relaxing the admission rule. The reason counts
overlap. A final pass with no remaining welds records an uninspected state with
null inspection counters, not a measured clean link inspection.

All five alternative depth profiles are rejected by the corrected rule on this
fixture. In particular, `72x3` and `96x3` increase boundary edges and are rejected
even though their reported intersection counts fall. No production profile,
normal estimator, depth threshold, reconstruction limit or topology gate is
promoted by this change.

The diagnostic-only native change preserved all non-diagnostic result fields
and GLB/PLY bytes on textured three-view, masked three-view and textured two-view
fixtures. That establishes bounded same-input parity, not physical quality.

## Validation boundaries

The original source was checked in a separate immutable worktree. Its release
native suite passed **53/53 CTest cases**. Its pinned host Python suite passed
**4,468 tests plus 362 subtests**, with **33 skips** and no failures. Those skips
remain unavailable checks; they are not passes.

The candidate's rebuilt release native suite also passed **53/53 CTest cases**.
Python and receipt evidence is recorded below after integration. A local
host pass cannot replace actual Android compilation, instrumentation, installed
offline workflows or physical ARM64 measurements.

The local GCC ASan/UBSan build succeeds, but executing even an empty instrumented
C++ program aborts with LeakSanitizer's ptrace-environment fatal error. The local
sanitizer execution gate is **BLOCKED_ENVIRONMENT**, not passed. No leak option,
ordinary-exit requirement or sanitizer gate is disabled.

The baseline's exact
[GitHub Verify run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40944757140)
remains failing: its GCC sanitizer report contains **4,863 cases, 4,822 passed,
13 failed and 28 skipped**. Failures occur at ordinary Python/JVM process exit;
scanner-free controls also report leaks. This attribution does not waive the
failures. Candidate CI must be judged from its own commit and retained artifacts.

## Remaining M20 work

Follow the [physical handoff](M20_PHYSICAL_HANDOFF.md). Required external inputs
include an authorized ARM64 handset, calibrated captures of at least two matte
textured objects, independent reference geometry and actual resource/appearance
measurements. Those physical results remain **BLOCKED_EXTERNAL**. Unexecuted
installed-app and release checks remain **NOT_RUN**.

The supported reconstruction range remains 2–12 input photographs and at most
eight dense reference views. Larger acquisition archives are not evidence that
the engine accepts larger reconstruction projects. Unknown surfaces stay
unknown, and a fragmented open mesh must not be described as complete or
watertight.

## Integrated evidence

The [compact evidence bundle](evidence/m20-2026-11-19/README.md) preserves the
actual receipts, including their original source context. Native measurements
identify baseline HEAD `a373aff` with a dirty worktree and exact helper/library
hashes. They must not be relabeled as execution against the eventual published
commit. A binary hash identifies tested bytes; build-source provenance is
explicitly unavailable in those receipts.

The first full candidate Python run completed with **4,955 passing parent tests,
362 passing subtests, one failure, 33 skips and zero errors**. All 488 new test
identities passed; every skip identity and reason matched the baseline. The
tested staged tree was `4ab77b5a2cf637e0e0b743acea59dbe825f21651`, with manifest
SHA-256 `6a9e5f7a7a56f0ed390186c0ca8f33f6c1de832ce1aa6e4bd560441b23e5f033`.
That run remains a failure in the retained evidence.

Its single failure occurred in the existing synthetic Clang-provider fixture.
A deterministic control showed that unrelated sibling creation changed shared
`/tmp` directory-entry timestamps while provider nodes and driver hashes stayed
unchanged. The exact unrelated writer in the original failed run was not
recorded. The corrected test fixture still runs the production validator, but
normalizes volatile epochs only above its owned synthetic installation. It
retains structural/ownership checks and full owned-node metadata. Production
`header_provider.py`, schema-2 proof semantics and compiler/header gates are
unchanged. All 101 focused provider tests pass, including sibling-activity and
owned-node mutation controls. The subsequent full run has its own result.

The exact remote candidate remains subject to its own GitHub checks; historical
green jobs do not qualify a later commit. See also the separate
[sanitizer evidence record](M20_SANITIZER_STATUS.md).

## Ten-wave assignment record

The completed assignment ledger and final full-suite outcome are recorded after
the remaining integration checks finish. Only actual assignments and executed
checks belong in that record.
