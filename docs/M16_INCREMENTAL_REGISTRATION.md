# M16 — incremental sparse registration beyond the anchor intersection

## Scope

M16 retains the two-view initializer and established native solvers. It adds a
bounded registration pass when some of the selected photographs remain unaligned.
The app still considers at most twelve photographs; up to eight registered views
feed the M15 adaptive depth and photographic-mesh pipeline.

Previously every additional camera had to observe features present in BOTH
original anchor images. Moving around an object can hide those features. M16
re-triangulates supported feature tracks among the currently registered cameras,
then registers an unseen view from those landmarks. A successful camera is added
to the common frame; the track graph is rebuilt before attempting another view.
Candidates that failed earlier are retried after the map grows. If no view can
be added, the loop stops and remaining images are explicitly unregistered.

## Data integrity and numerical checks

- Match caches contain only reciprocal keypoint index pairs: at most 66 pairs of
  at most 4,000 matches for twelve images. No descriptors or bitmaps are retained
  in this cache. These are allocation limits, not measurements of process RSS.
- Track snapshots preserve the exact track IDs sent to native triangulation.
  Whole-track packing enforces the 100,000-observation native bound.
- A landmark's contributing observations must have positive camera depth and
  satisfy reprojection limits. A new keypoint must be matched consistently from
  at least TWO such registered observations. Any disagreement rejects the track.
- If two landmarks claim the same candidate keypoint, BOTH are discarded.
  At most 5,000 unique XYZ/normalized-UV rows enter native PnP.
- The returned rotation, inlier IDs, inlier counts, positive depths and individual
  reprojection errors are independently checked before a camera is accepted.
- The original two anchor poses are never modified by this registration pass.
  All later camera/point refinement runs through the existing M03/M06 stages.
- The final track graph considers all registered pairs (at most 66), including
  nonadjacent capture indices, rather than discarding a recovered overlap edge.
- Registration failures do not invent camera positions. Failure of the extra
  pass preserves accepted anchor-based geometry and reports a separate reason.
  Cancellation is propagated. Native calls themselves are still noninterruptible.
- New artifact identity uses `m16-registration-frontier-1`. Original photos and
  previously exported files are not altered.

## Regression evidence and how to interpret it

Eight JVM cases cover two-view support from later cameras, conflicting features,
duplicate candidate identity, stable snapshot IDs, malformed reciprocal pairs,
disconnected views, cancellation and a native result claiming invalid inliers.

The Android `IncrementalNativeRegistrationTest` uses 210 synthetic noncoplanar
feature positions split into three overlapping visibility groups and six image
slots, one deliberately disconnected. Image order is scrambled: capture
positions 0,1,4,3,2,5. Only the original two poses are supplied. The production
native triangulator and PnP solver must grow the camera sequence 0,1,4,3,2,
retrying initially unsupported candidates, while leaving slot 5 unregistered.
Camera translations/rotations are compared against withheld synthetic truth.
This tests registration with analytic feature correspondences, NOT photographic
feature detection or physical 360-degree scan quality.

An independent authoring-host native experiment with the same visibility
structure recovered five cameras from two seeds using 70 inliers at each step.
This is a synthetic numerical sanity check, not a replacement for the actual
Android helper test. Existing photograph-based full-session emulator tests and
real Middlebury depth/surface/texture benchmarks remain required CI gates.

## Remaining work

Physical ARM64 captures, larger unstructured scenes, loop-closure/drift control,
intrinsic optimization, textureless/repeated-pattern robustness, reliable
process-death resumption, native cancellation, thermal profiling, higher-detail
dense reconstruction, mesh topology and release readiness remain open.
A green test run does not establish a complete or perfect scanner.

Algorithmic background: incremental image registration, triangulation and bundle
adjustment are distinct steps in COLMAP's reference documentation:
https://colmap.github.io/pycolmap/pycolmap.html
https://colmap.github.io/cli.html
M16 implements a first-party bounded controller over RealityForge's own solvers;
it does not incorporate COLMAP code or add an external runtime dependency.
