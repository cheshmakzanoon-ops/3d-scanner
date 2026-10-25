# Supplementary standalone native C ABI replay

**Not a release-qualification gate, Python sanitizer workaround, or scanner leak-free claim.**
The existing native/Python sanitizer workflows and all of their failures remain unchanged.

This diagnostic prepares inputs in ordinary uninstrumented Python, then ends every
native replay through an independent executable's normal return. Sanitizer builds
instrument **both the driver and full candidate library**, not CPython or NumPy.
No sanitized candidate is loaded into the Python controller. The process boundary
isolates selected native workload behavior; it does not validate ctypes lifetime.

## Run

Install the existing hashed `tools/requirements.lock` and official CMake/Ninja,
C++ compiler and zlib development tools. Use a clean controller environment without
`LD_PRELOAD`, `LD_AUDIT` or sanitizer-option overrides. Evidence directories must
be fresh and outside the source checkout:

```sh
python -m pytest -q tests/test_native_cabi_replay.py
python -m tools.qa.native_cabi_replay.run --config normal --output /tmp/replay-normal
python -m tools.qa.native_cabi_replay.run --config ubsan --output /tmp/replay-ubsan
# On a host where ordinary exit LeakSanitizer is supported:
python -m tools.qa.native_cabi_replay.run --config sanitize --compiler g++-13 --output /tmp/replay-sanitized
```

A normal/UBSan run does **not** count as ASan/LSan evidence. Local LSan currently
encounters a ptrace fatal error even in an empty native control. Do not bypass it,
change security settings, suppress reports or disable leaks. The separately named
hosted diagnostic is the intended ASan/LSan execution environment.

The new workflow runs manually or for relevant main source/diagnostic changes.
It is additive, read-only with respect to GitHub, and does not modify any original
qualification workflow. Its successful job means only the selected diagnostic
completed; it does not waive failing Python gates.

## Workloads and ownership

- Synthetic sparse observations reuse `tests/test_pipeline.py::observations` and
  export GLB/PLY. These are explicitly synthetic, not captured object evidence.
- Real calibrated Motorcycle stereo preparation captures the existing surface and
  texture benchmark's exact NativeEngine call arguments before any native call.
  Expected method and exact export-only keyword arguments are checked before
serialization, alongside image/camera shape relationships and byte types.
Depth inputs are228×154; texture photographs are608×410; parameters remain
  near6/far80/64planes/stride3. The benchmark's dataset loader also loads disparity,
  but ground truth is never serialized into native input or used by the driver.
- Each of the original three native processes executes two repetitions, each containing
  successful export, deliberate export-path error, null-input error and successful
  recovery. RAII releases every non-null returned JSON through `rf_free`, including
  output-write exceptions. The driver also checks `rf_free(nullptr)`.
- A fourth, explicitly synthetic calibrated-planar workload reuses the existing
  `calibrated_initializer_fixtures.hpp` 194-match anchor. Preparation forward-projects
  normalized rays with nonzero radial and tangential distortion into observed pixels
  in two 384×288 domains with different intrinsics and distortion. No truth pose or geometry enters the input. Its direct
  `rf_reconstruct_calibrated_json` calls run twice with successful reconstruction,
  short observed/domain counts, each of the three null buffers, swapped domains, fractional image
  dimensions, misaligned and nonfinite observed pixels, an equivalent zero-distortion
  domain, and successful recovery. Every returned JSON uses the same RAII `rf_free`
  ownership path. This API has no export parameters; it does not substitute an
  export test for the original workloads. The verifier requires the exact call set,
  homography/sparse/arbitrary-scale representation, exactly159 retained points from
  the194raw/162distinct-pair anchor and unique **first-occurrence original row**
  indices, deterministic success/error replies, and exact recovery
  and domain-equivalence output. Ordinary ctypes parity uses a diagnostic-local
  calibrated signature and `finally` release, not a new production Python wrapper.
- File extents, format, dimensions, architecture and repetition bounds are checked
  before reconstruction. Truncated, trailing, bad-format and extreme-count files
  must fail before creating outputs.
- The controller checks JSON error/partial-result contracts, deterministic repeated
  responses/artifacts, finite JSON values, sparse versus triangle representation and exact photographic
  PNG embedding. Only `normal` additionally compares actual ordinary ctypes JSON.

The old calibrated-planar verifier required at least170 points and previously
observed191 duplicate-inflated rows. Those results remain historical evidence, not
current support. The distinct-pair native regression independently establishes
194raw/162distinct/159supported for this unchanged fixture. Its verifier now
checks those exact counts and derives first-row representatives from the source
snapshot fixture; it does not lower an independent physical-quality threshold.
Two registered cameras remain two, not global three-view success. A changed
fixture cardinality, fewer points, inflated duplicates or later-row IDs fails.

The controller requires all four expected workload names and modes; missing,
extra or substituted modes fail. Calibrated binary truncation, trailing bytes and
extreme counts also fail before output creation. The modest distortion deliberately
preserves this anchor's accepted domain coverage: exact equality to the undistorted
reference is a property checked for this fixture, not a claim that distortion can
never change support. Native initializer tests separately exercise changed observed
cell membership and ambiguous/unstable planar hypotheses.

Inputs have a fixed little-endian versioned binary header and typed contiguous
buffers; their hashes, extents and settings are recorded. Successful and failed
replay outputs remain available for inspection. No large fixture is committed.

## Evidence and failure behavior

The controller snapshots the current source tree, records HEAD and dirty status,
and hashes every snapshotted file. Builds require every reviewed campaign provider/test with
`RF_REQUIRE_CAMPAIGN_031_060=ON`; missing providers fail configuration rather
than silently reducing the native target. Ordinary ctypes parity imports its
controller from the snapshot too. Builds use that snapshot, so receipt identity is
not merely an assertion that the checkout equals HEAD. Receipts also record compiler,
full native library, executable, input, log and output hashes, exact commands and
exit statuses. Evidence is retained on preparation/build/runtime/verifier failures
and timeout. Each subprocess has a deadline and32MiB log budget; exceeded limits
fail rather than qualify the run. Existing evidence directories are never reused.

ASan replay enables normal exit leak checking and requires both clean and deliberately
leaking controls from the existing `python_lsan_comparison/control.c`. The planted
control must exit23 with exactly one direct1237-byte/one-object leak, a
`planted_leak` stack frame, the expected LeakSanitizer header and exactly one
matching1237-byte/one-allocation summary. Missing totals, additional direct or
indirect leaks, duplicate blocks, other sanitizer findings and fatal reports fail. A timeout, fatal runtime error, wrong leak, missing detection,
scanner leak, unexpected native error or verifier failure fails this diagnostic.
The intentionally failing positive control is the only expected nonzero leak exit;
its classification never applies to scanner workloads. Invalid fixture tests have
separately declared expected input-rejection exit1 and must print only the exact
expected driver rejection reason. Successful replay and empty-control logs must
be empty; any unexpected output fails closed even with exit0.

## Explicit limitations

Coverage is four representative C ABI workloads, not every allocation path/API.
It does not cover Python/NumPy shutdown, ctypes declarations or wrapper-finally
lifetimes, camera registration/BA, compact textures, cancellation/checkpoint APIs,
JNI/Android lifecycle, physical multi-side quality or release readiness. Normal
ctypes output equality is numerical parity only, never Python leak qualification.
A clean standalone run cannot prove all previously failing Python tests external,
nor prove the scanner leak-free. A standalone leak also requires attribution among
driver, native implementation and native dependencies.
