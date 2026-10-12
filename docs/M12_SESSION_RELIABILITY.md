# M12 — completed-session integrity and end-to-end Android validation

## Fixed failure paths

- Saved project JSON was previously read with an unbounded readText and no
  cross-check between file and database revision. Reopening now bounds input to
  32 MiB, checks JSON nesting/truncation, exact integer revision, artifact identity,
  and rechecks the completed database snapshot after I/O. Invalid files do not
  become a current preview; original photos remain untouched.
- A delayed project-selection load could republish a stale preview during a new
  operation. Selection loads are cancellable and versioned; final publication is
  on the main thread only when the same selection and idle state still apply.
- Model export and project deletion previously did not reserve the busy state.
  All UI operations now reserve it before suspension. Export copy is cancellable;
  its source revision is checked before/after. Errors explicitly warn that the
  selected destination may be incomplete. No destination is deleted automatically.
- Capture ZIP outputs are closed even when preflight validation fails. A changed
  project revision prevents writing the capture session success manifest. Original
  photo hashes are still verified while copying.
- Unattached camera temporary files are removed when there is no selected project.

## Tests

M11 full run 38198779585 passed 20 emulator tests, including real EGL color
readback, masks/context loss, actual dialog activity pause/resume, and native GLB
compatibility. M12 adds four cases in SessionReliabilityTest:

1. The actual Android ViewModel/pipeline imports the existing three SYNTHETIC
   relief photos; uses their explicitly synthetic known intrinsics; reconstructs
   a photographic model; reopens it through database metadata; exports exact GLB
   bytes; preserves original hashes; and renders the generated file in the real
   photographic dialog. The test records a screenshot before emulator shutdown.
2. Completed preview loading rejects mismatched, string/fractional and stale
   revisions. Existing database revision guards reject late completion.
3. Oversized, truncated and deeply nested JSON fails without deleting projects.
4. A capture export interrupted by a revision change never reports success and
   does not alter originals.

Tests use test-only assets from benchmarks/fixtures, NOT release APK assets. A
known synthetic LensProfile in the fixture is not a fabricated real calibration.
The screenshot depicts a synthetic reconstructed surface, not a physical scan.
The busy-state negative check deliberately reserves the state; it is separate
from the real successful artifact-copy test and is not a timing benchmark.

Local CTest and Python tests validate source/engine regressions, not Android
execution. Run-specific Android compilation, all instrumentation outcomes and
screenshot must be checked before declaring this increment verified. The device
script propagates failures and fails when its promised screenshot is missing.

## Still not complete

Native reconstruction remains bounded and cancellation occurs at safe stage
boundaries; no new process-death checkpoint/resume algorithm is claimed. No
physical ARM64 temperature/memory/battery data, complete-object reference scans,
production topology/atlas quality, high-resolution MVS or release signing is
provided by this phase. Reconstruction fingerprints are unchanged (M10 engine);
M12 changes session behavior and app/test versions only.

## Screenshot collection correction

The initial M12 commit passed all 24 Android instrumentation tests, but AGP
uninstalled the test application before the post-Gradle `run-as` capture. ADB
reported a successful process exit while its output contained an error rather
than PNG bytes. That output was rejected during independent artifact inspection.
The instrumented test now captures through UiAutomation's shell identity into
/data/local/tmp before teardown. The collector pulls that file, validates PNG
signature, chunk ranges, CRC, dimensions and completion, and removes the temporary
file. Regression tests reject the exact old error text and corrupt/truncated PNGs.
This capture path exists only in instrumentation, not in the shipped application.
