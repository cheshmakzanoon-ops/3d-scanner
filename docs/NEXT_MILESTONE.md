# Next implementation gate: M11 — actual photographic model inspection

M10 corrects face-normal and UV-origin bugs and introduces measured exposure
balancing and visibility-safe source-label coherence. Its real-data result is a
calibrated two-view stereo test, not an all-sides object capture.

1. Load RealityForge's self-contained indexed photographic GLB in a bounded
   on-device texture renderer. Preserve the vertex-color diagnostic fallback.
2. Validate GLB offsets, indices, finite attributes, embedded PNG budgets and
   actual texture orientation before accepting the asset. Reject corrupt files
   without unsafe allocations or accidentally following external image links.
3. Test Android viewer lifecycle, import/reopen/export, cancellation, process death
   and file-publication integrity. A green compiler is not rendering evidence.
4. Expand authorized physical ARM64 capture benchmarks: two rigid textured objects,
   12–40 photos, the printed 9×6-inner-corner checkerboard, independent geometry,
   actual camera registration/completeness and RAM/thermal/battery logs.
5. Address overlap/nonmanifold surfaces and materially denser adaptive MVS before
   advertising high-resolution, full-object or watertight scanner output.

## Release blockers that remain

No physical-phone capture session or sustained resource profile has been tested
here. Dense Android processing is still limited to three low-resolution views;
texture charts are camera tiles rather than a compact, seamless atlas. Full-object
coverage, calibrated intrinsic refinement, robust loop closure, topology cleanup,
LODs, production model-studio controls and store readiness must be demonstrated,
not inferred from isolated successful tests. Continue PROJECT_PLAN.md's twenty
phases without substituting generated surfaces for unobserved geometry.
