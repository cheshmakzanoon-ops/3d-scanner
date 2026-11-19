# M20 physical-device evidence handoff

Status on 19 November 2026: **preparation only**. The software campaign is recorded
in [M20 preparation](M20_PREPARATION_2026_11_19.md). The app remains the
`0.19.0-m19` engineering preview. No physical capture, handset measurement,
independent reference comparison or production-signing result is supplied by
this document. Those inputs remain **BLOCKED_EXTERNAL**; individual checks that
have not executed remain **NOT_RUN**.

M20's next evidence is repeatable reconstruction and topology measurement on an
actual ARM64 phone. Keep the current **2–12 reconstruction photographs and at most
eight registered dense views**. The capture protocol's 12–40-photo acquisition
archive is a collection target, not permission to reconstruct more than 12
inputs. Select and hash the admitted subset before running; reserve independent
held-out photographs separately. The installed physical journey adapter accepts
3–12 photos, a narrower requirement of that test.

## 1. Freeze the candidate and measurement contract

Use a clean committed source checkout. Build and validate through
[BUILDING.md](../BUILDING.md), retaining actual native, Android compilation,
lint, JVM and instrumentation results. A host pass does not close a failed
sanitizer or device gate. Record the source commit, source-manifest identity,
toolchain and engine/settings versions, native library hash, and exact app/test
APK hashes. Record the AAB hash when an AAB has actually been built; an absent
artifact is unknown. Keep test-signed and production-signed packages distinct.

Before inspecting candidate results, freeze the hashes of the
[geometry protocol](../../benchmarks/quality_protocols/geometry.json),
[texture protocol](../../benchmarks/quality_protocols/texture.json),
[UI performance protocol](../../tools/device/ui_performance/protocol.json),
input/eligibility manifests and reference assets. The
[corpus contract](../../benchmarks/corpus/README.md) defines the required
provenance. Do not change eligibility masks, alignment landmarks or thresholds
to remove a failed part of a scene.

Create a fresh private evidence directory for each run. Existing device and
quality tools may write their requested output paths; never reuse a previous
run's directory. Preserve original photos, raw traces and complete reports
outside Git. Commit only reviewed summaries/manifests/hashes. A hash proves byte
identity; acquisition authorization and operator observations still need review.

## 2. Acquire two independent object sessions

Follow the [physical capture protocol](../device-protocols/physical-capture.md)
for **two distinct authorized matte textured rigid objects**, with diffuse
lighting, multiple heights and a full orbit. Retain the device model, OS/build
fingerprint, selected physical/logical camera, fixed optical lens and 1x zoom,
crop/orientation, focus/exposure strategy, ambient conditions and session/project
revision. Glossy, transparent, moving and low-texture cases stay separately
labeled adverse tests.

Use the same camera/lens/crop for 10–40 checkerboard views and retain the app's
accepted calibration profile and all source hashes. Obtain a separately measured
reference mesh with its method and units; the reconstructed mesh cannot be its
own reference. Freeze corresponding landmarks and independent reference-visible
samples. A similarity alignment for evaluation does not establish metric scale
in the reconstructed asset. Hold-out photographs and visibility masks must be
independent of reconstruction and texture selection.

After installing the selected debug app/test APKs on an authorized disposable
test installation, use the existing identity and capture-audit commands from the
repository root. Replace the uppercase paths and identifiers with real inputs:

```sh
python tools/device/capture_validation/qualify.py discover \
  --serial SERIAL --app-apk APP.apk --output PRIVATE/device.json
adb -s SERIAL shell am instrument -w -r \
  -e class com.realityforge.scanner.qa.PhysicalCaptureContractTest \
  -e physicalProjectId UUID -e physicalCameraId CAMERA_ID \
  com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner
python tools/device/capture_validation/qualify.py audit \
  PRIVATE/session.json PRIVATE/capture.zip \
  --device PRIVATE/device.json --output PRIVATE/capture-audit.json
```

Construct `session.json`, checkerboard manifest and exported capture ZIP exactly
as the linked protocol specifies. Retain the raw instrumentation transcript and
its `physicalCaptureReceipt`. Discover rejects emulators and installed APK
mismatches; the audit verifies retained evidence and original-byte identities.
Neither command proves geometry quality or automates the required CameraX
permission, interruption, rotation and focus/exposure observations.

## 3. Exercise the installed offline journey and recovery

Prepare the exact candidate JSON and bounded physical bundle described in
[release qualification](../release/qualification.md), then execute:

```sh
python -m tools.release.qualification.journey \
  --candidate PRIVATE/candidate.json --apk APP.apk --test-apk TEST.apk \
  --serial SERIAL --physical-bundle PRIVATE/physical-bundle \
  --output PRIVATE/installed-journey
```

This checks the actual installed package identities, import/calibration,
reconstruction, photographic render, export, reopen and original preservation.
Retain the complete transcript, receipt and exported bytes. Its PASS covers the
selected test; camera capture, offline radio verification, cancellation,
process death, physical quality and resources remain separate gates.

Record the initial radio state, disable network connectivity and repeat the
operator's physical capture/import → reconstruct → inspect → export → reopen
journey; restore the previous radio settings afterward. The existing
`tools/device/offline_privacy/journey.py` runs a **synthetic** installed
regression. Its result must keep that label and cannot replace the physical
offline journey.

Exercise pause/cancel and process death on disposable projects at import,
registration, mesh/texture, checkpoint and publication boundaries. The existing
external-process controller supports these five named stages; for example:

```sh
python tools/device/failure_recovery/controller.py \
  --serial SERIAL --source-sha SOURCE_COMMIT --stage checkpoint \
  --app-apk APP.apk --test-apk TEST.apk --output PRIVATE/recovery-checkpoint
```

Run the other stages in separate new directories. Check original and completed
artifact hashes, stale-input invalidation and absence of partially published
results. These dedicated regression fixtures do not replace operator evidence
on the selected physical project. Do not uninstall or clear existing user data
to obtain a clean result.

## 4. Measure resources and retained image lifetimes

Follow the [resource protocol](../device-protocols/resource-profiling.md). Start
with the admitted 12-photo/eight-dense-view workload, recording the actual views
registered and processed. Collect cold launch, warm repeat, at least three
sustained reconstructions and a ten-minute photographic-viewer trace under
recorded ambient, brightness/refresh, battery/charging and background-load
conditions. Do not infer that all eight dense views ran from the input count.

The protocol's `ResourceProbeContractTest` drives the production pipeline on an
operator-selected disposable project. Run the serial-bound sampler concurrently:

```sh
python tools/device/resource_profiling/profile.py collect \
  --serial SERIAL --app-apk APP.apk --input-manifest PRIVATE/input.json \
  --settings PRIVATE/settings.json --conditions PRIVATE/conditions.json \
  --samples 120 --interval 1 --output PRIVATE/trace.json
python tools/device/resource_profiling/profile.py analyze PRIVATE/trace.json \
  --output PRIVATE/resource-summary.json
```

The 120-sample example is bounded collection, not proof that the entire workload
was covered. Choose a supported duration that spans the relevant phases and
retain raw timestamps. The current tooling reports sampled RSS/PSS, Java/native
heap/allocation counters where available, storage, thermal status, battery
indicators and stage timings. Missing counters remain null. Its PASS means
**trace integrity**, not acceptable memory, heat, speed or power consumption.

Complete these additional measurements explicitly:

| Evidence | Existing support and remaining requirement |
|---|---|
| CPU and sustained thermal behavior | Correlate stage events with an actual supported system trace; elapsed duration alone is not CPU utilization. Preserve process-start identity and thermal transitions. |
| Rendered frames and latency | Use the [UI performance protocol](../quality-protocols/ui-performance.md) and actual AndroidX traces. Required peak/input counters that remain unavailable keep qualification open. Synthetic viewer fixtures measure their stated workload only. |
| Image/Bitmap/OpenCV Mat lifetimes | Retain a versioned trace or instrumentation of acquisition, live bytes and release at real pipeline boundaries. The resource sampler does not prove individual buffer lifetimes; absent instrumentation remains NOT_RUN. |
| Power | `energyJoules` intentionally remains null in the resource analyzer. Precise energy needs calibrated external hardware or documented supported counters, including charging/background-load conditions. Battery percentage is not measured joules. |
| Peak memory | Report sampled peaks and interval limits, plus a reviewed method for transients where required. Host C++ structure sizes do not establish peak ARM64 RSS. |

Use a reviewed hardware/profile budget contract before interpreting these
measurements. The UI protocol has its own frozen thresholds; it does not supply
a universal reconstruction-resource budget. Unsupported required counters or
missing budgets cannot become zero or PASS.

## 5. Evaluate geometry, appearance and exported assets

Run the existing evaluators on descriptor files that bind the exact candidate,
engine/settings, corpus manifest, independent reference and eligibility data:

```sh
python -m tools.qa.geometry_quality PRIVATE/geometry-scenes.json \
  --output PRIVATE/geometry.json
python -m tools.qa.texture_quality PRIVATE/texture-scenes.json \
  --output PRIVATE/texture.json
```

The frozen geometry protocol requires at least 95% camera registration and 95%
observable completeness at 1% reference-bounding-box-diagonal tolerance, p95
surface error at most 1% of that diagonal, and zero duplicate triangles,
nonmanifold edges and self-intersection pairs. Incomplete topology analysis does
not establish zero defects. Preserve boundary edges, disconnected components and
missing-side observations; an open observed patch is not a watertight object.

The texture protocol separately requires, among its fixed conditions, at least
95% original-face support, 95% held-out support and linear-RGB RMSE at most 0.05.
Retain every per-scene result, unknown-alpha/visibility region and visual review;
do not hide failed scenes in an average. Check all other UV, seam, distortion and
support conditions in the linked protocol. Independently import and inspect the
actual exports through the [interoperability protocol](../quality-protocols/export-interoperability.md),
including units, materials and companion-file identities.

The geometry tool's broader `physical_gate` also requires adverse scenes and
12/30/100/250-photo workloads. A bounded M20 12-photo measurement cannot close
that broader release matrix. Leave unsupported larger profiles open and retain
both the per-scene result and aggregate gate status; do not edit the protocol to
obtain a pass.

## Completion record

For each object/device/candidate tuple, retain exact identities, original
measurement timestamps, raw evidence hashes, individual PASS/FAIL/NOT_RUN/
BLOCKED_EXTERNAL states and failure explanations. New code or settings require
a newly frozen candidate and the affected measurements again. Reopening a saved
preview retains originating diagnostics; it does not measure fresh work.

M20 physical evidence is ready for review only after the two authorized sessions,
independent geometry/held-out appearance, installed offline/recovery journey and
mandatory ARM64 resource measurements actually execute against the same frozen
candidate. Production signing, packaging, accessibility, security/license review
and external beta retain their separate gates in
[release qualification](../release/qualification.md). This handoff neither
waives those gates nor changes production defaults, admission limits or versions.
