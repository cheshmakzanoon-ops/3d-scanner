# Next gate after M12 — complete-session reliability and measured scan quality

M10 corrected face normals and glTF UV orientation and reduced exposure mismatch.
M11 adds bounded local photographic GLB inspection and real shader/lifecycle tests.
M12 adds revision/operation safety and a complete synthetic Android session test.
None of these results establishes physical full-scene scanning.

1. Exercise a complete import → reconstruction → photographic inspection →
   reopen/export session, with malformed profiles, process death, cancellation,
   storage shortages and changed inputs. Preserve originals and never publish a
   partly complete result. Add exact regressions for every observed defect.
2. Collect authorized physical ARM64 capture bundles of at least two matte
   textured objects, 12–40 photos, a same-lens checkerboard profile, independent
   reference geometry and actual peak RAM/thermal/battery measurements.
3. Remove the three-low-resolution-view bottleneck using benchmarked incremental
   reconstruction, visibility/coverage selection, checkpointing and memory bounds.
   Measure completeness, not only retained-point error on one stereo pair.
4. Improve nonmanifold/overlapping triangle diagnostics, conservative cleanup,
   compact atlas charts and blending. Do not invent unseen surfaces or silently
   close genuine openings. Add mesh optimization/LODs and further export formats.
5. Build the production model studio and release workflow only after the above
   geometry, appearance and device evidence is available. The Filament studio,
   store-ready AAB, signing, accessibility, localization and external beta gates
   from PROJECT_PLAN.md remain open.

No claim of perfection or release completeness follows from compiler success,
synthetic rendering tests, or isolated rectified-stereo reconstruction results.


## After M15: complete-object registration and device-scale execution

M14/M15 replace the three-input depth bottleneck with up to eight reference views,
three selected stereo sources per reference, and bounded adaptive Android decode.
Next validate calibrated physical captures and actual phone resource consumption;
expand registration beyond the anchor intersection; add restartable per-reference
depth artifacts and cancellation before increasing view/resolution limits again.
Do not substitute the synthetic plane coverage test for full object completeness.

## After M16: native cancellation and restartable work

Registration now grows beyond the original anchor intersection with fresh
triangulation and two-view-supported PnP. The twelve-photo sparse cap remains.
Next implement operation-scoped native cancellation without global interference,
then restartable per-reference depth checkpoints with strict input/calibration/
engine identities. Validate process interruption and unchanged completed exports.
Physical complete-object captures, intrinsic/drift correction and high-detail
multi-view benchmarking remain required; do not raise limits without measurements.


## After M17 — M18: checkpointed per-reference depth work

M17 now stops a running CPU depth/mesh/texture job cooperatively without affecting
another operation in the same process. Next persist **per-reference** depth
checkpoints with SHA-256 identity of photographs, calibration, registered poses,
engine version, depth settings, and application revision. Create outputs through
atomic temporary writes, refuse mismatched/residual caches and validate resume
across actual Android process termination. Partial files must never be exposed
as finished models. This is separate from any claim of real-world 360° quality.

## After M18 — M19: physical capture truth and resumable full sessions

M18 checkpoints only accepted per-reference photometric depth, never partial
surface/texture outputs. Next collect same-lens calibrated images of actual
textured objects on an ARM64 device; validate memory, temperature and object
coverage against reference meshes. Then add on-device interruption/relaunch
instrumentation, explicit checkpoint cache inspection/clearing, and better
cross-view fusion to avoid redundant overlapping faces before raising the
resolution. Do not claim release completeness from emulators or synthetic rigs.
