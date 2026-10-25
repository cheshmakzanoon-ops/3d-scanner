# Next integrated-candidate gates after M19

The current engineering preview retains version 0.19.0-m19 with subsequent
validation tracked in [current status](MILESTONE_STATUS.md).
Historical milestone results do not certify the current candidate.
Integrated paths still require commit-specific runtime and quality qualification.
Supported reconstruction input remains 2–12 photographs; do not raise limits or
claim a feature from a contract/document alone.

Required next evidence: immutable candidate source/APK/AAB hashes, actual native
and Android compile/lint/JVM/instrumentation, installed offline end-to-end journey,
authorized physical object/reference captures, whole-visible-surface geometry and
held-out texture quality, ARM64 memory/thermal/power, target-tool exported assets,
security/license inventory and signing/packaging qualification. Unavailable external
inputs remain BLOCKED_EXTERNAL; unexecuted checks remain NOT_RUN.

Use the pinned [host QA dependencies](BUILDING.md#independent-host-qa-dependencies)
when reproducing validation. All candidate gates remain required. An ordinary
compatibility suite cannot close full instrumentation, capture, recovery,
offline operation, physical profiles or release journey requirements.

The integrated source now includes service-owned durable reconstruction jobs,
operation-scoped sparse/dense cancellation and identity-validated checkpoints,
including per-reference depth reuse. This does not establish recovery at every
interruption boundary or successful completion of every installed-app workflow;
those checks remain explicit gates in the current status. Unknown surfaces stay
unknown, scale stays arbitrary without measurement, and open patches stay open.

## Immediate validation priorities — 25 October 2026

The fixed licensed three-photo [HOST journey passes on `f6cdd12`](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40220547113):
all three cameras register, global typed bundle adjustment completes, and nine
blobs / three exports / publication / reopen are verified. This is not installed
UI or physical-quality qualification. Android checks pass with 920 tests;
full native CI still fails. The synthetic `5beb2d3` journey and selected `7ff6395`
Filament lifecycle pass remain historical source-bound evidence.

**Engineering APK update warning:** these builds use ephemeral test certificates;
compatible in-place updates are not verified. Export important original captures
and models before considering uninstalling. Do not assume a backup exists or that
uninstalling preserves app-private data. These are not production-signed releases.

1. Complete the corrected installed synthetic journey. The `7ff6395` run failed
   at DocumentsUI selection before import/reconstruction; `c3f26f3` repairs the
   driver without bypassing the picker. Run and retain actual rendered UI, SAF,
   reconstruction, reopen/export, cancellation and interruption evidence.
   The [5/5 software ATD native/cache/PnP pass](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40225784945)
   belongs to `fac55e9`: actual SDK build/install and numeric JNI, zero skips or
   failures, on API35 x86_64 unsupported software TCG. It is not UI/SAF/Filament,
   ARM64, a complete real-photo journey or physical-quality qualification.
2. Improve dense coverage and investigate the **zero cross-view fusion** reported
   by the `f6cdd12` diagnostics. Third-camera registration is no longer the blocker
   on this fixed fixture: all three register, with 45 tracks for the third camera
   and global typed bundle adjustment. Preserve that verified progress while
   measuring why the 2,469 dense points / 1,296 triangles still form a fragmented
   result; do not lower geometry gates or invent missing surfaces.
   The earlier failed RootSIFT trial remains rolled back. The
   [independent COLMAP reference](development/experiments/skull-colmap-reference.md)
   remains a comparison, not physical ground truth or scanner quality acceptance.
3. Continue measured topology repair beyond the supported-diagonal change.
   The `5beb2d3` synthetic result still has 653 nonmanifold edges,
   113 components and 9,542 boundary edges; intersection analysis is incomplete.
   The real `f6cdd12` mesh has 1,618 vertices, 1,580 boundary edges and
   558 reported intersections despite zero nonmanifold edges; it remains visibly
   fragmented and is not watertight.
   Exact-quad consistency is not general surface fusion or hole repair. Do not
   hide defects, fabricate unseen surfaces or label open meshes watertight.
   The `813f312` exact planar ownership QA passes its dedicated sanitizer and
   release cases but remains OFF by default; production integration and mobile
   qualification require separate evidence. It has not repaired this mesh.
4. Fix full-candidate sanitizer failures and obtain a green result. On `f6cdd12`,
   GCC and Clang each completed 4,513 cases: 4,468 passed, 17 failed, 28 skipped.
   All `f6cdd12` jobs are terminal; native/candidate/device gates fail. Later
   `fac55e9` native gates remain pending; its Android build passed 920 tests. Diagnose the 17
   failures without waiving them. Android/release builds, benchmark compilation
   and the actual HOST journey pass do not replace these gates or qualify
   installed workflows. Historical test-ID aborts are not current full-suite passes.
5. Validate calibrated physical ARM64 captures against independent reference
   geometry, texture and resource measurements before raising the enforced
   2–12-photo/eight-dense-view limits or claiming complete-object quality.
   Complete production signing and release qualification separately from
   test-signed APK builds.

The sections below preserve historical milestone plans; “next” in those sections
refers to their original milestone, not work still missing from today's source.

## Historical next gate after M12 — complete-session reliability and measured scan quality

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

## After M19 — M20: physical-device benchmark and topology evidence

M19 adds honest observed-evidence diagnostics rather than a misleading
"90% complete" score. M20 should instrument ARM64 process RSS, CPU/thermal
behavior, frame counts, power impact, and image/mat buffer lifetimes across
real 12-photo and 8-depth-view projects. Collect repeatable, authorized,
calibrated 360-degree sessions for at least two matte objects, compare with
external reference geometry and report missing sides, disconnected patches and
nonmanifold edges before increasing depth resolution or claiming completeness.
The emulator and synthetic datasets cannot replace physical handset validation.
