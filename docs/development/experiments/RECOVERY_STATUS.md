# Research evidence recovery — 23 October 2026

A cloud workspace interruption removed the unpublished working checkout, toolchains,
experiment sidecars and staging directory. Published application source at
`ba259b96d3fdcba8c304a8214764d0d70e51be80` was recovered unchanged from GitHub.
This documentation commit does not change production code or qualify the app.

## Byte-exact recovered packages

- [Focused Android ATD runtime](android-atd-focused-runtime/README.md): all seven
  files recovered from their already uploaded Git blobs. Every package checksum
  passes, and the published controller's strict parser reproduces the saved four-test
  proof. This is supplementary non-UI evidence only, not full device qualification.
- [Exact planar support union](exact-planar-support-union/README.md): all 61 files
  recovered from a previously uploaded recovery archive. Archive SHA-256:
  `250904960874ff0736f7fb83f09d68d15604fb4de9b0f9eb58bd17adab98311f`.
  The unchanged package manifest SHA-256 is
  `245a908e76756695d68f07198aa92177b787b5c23ecf57ee4e4af7d09cd4867e`;
  all listed files verify. Historical build logs retain their original whitespace.
  This is a narrow synthetic exact-plane experiment, not a photographic fusion fix.

These packages were reviewed before the outage. Recovery verifies byte identity;
it does not claim a new full geometry replay in the replacement workspace.

## Not recovered or promoted

Eight other staged research packages and the new global-connectivity prototype
were not committed. Their full sources, fixtures and replay outputs are not present
in this delivery. Historical summaries are not a substitute for those missing bytes.
No unpublished failed candidate has been installed in production. The latest
prototype had not executed a global front or produced a photographic candidate;
its calibrated adapter had not passed independent review.

A full local pytest run started before the interruption, but its final result is
unavailable. Do not count it as passing. Rebuilding the validation toolchain and
rerunning relevant checks is required for subsequent code changes.

## Published-source CI remains mixed

At exact source `ba259b9`, the targeted ATD job passed all four tests; Android
unit tests, build and lint passed. Release native matrix checks passed. Both
sanitizer matrix Python suites timed out despite passing their native CTest cases.
The aggregate Verify run
[39967166241](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39967166241)
failed and did not seal final native evidence. The original device gate also failed.
No sanitizer, physical-device, UI, performance or release requirement is waived.

## New reconstruction checkpoints

[Exact connectivity primitives R1](global-connectivity-primitives-r1/README.md)
is newly reconstructed source with new identities, not a recovered historical
package. Its independent rational-geometry, hole, sphere and bounded-execution
controls pass. It does not contain a calibrated adapter, global advancing front
or photographic result. The missing original datasets remain missing.

[New eight-view cuboid fixture](new-cuboid-eight-view-160x120/README.md) is a
separately generated synthetic dataset with pinned images and declared intrinsics.
It does not replace the missing historical three-view fixture or reproduce its
measurements. Render poses and reference geometry are evaluation-only; no
image-derived reconstruction result is claimed. The original executed renderer
is retained as historical evidence with its known replay-safety limitations.

[Closed support overlay R2](global-connectivity-overlay-r2/README.md) adds
reviewed exact whole-footprint and closed-boundary association/visibility checks,
including mandatory normal constraints for boundary-only contributing records.
This is a new primitive-only checkpoint, limited to abstract camera IDs 0–3;
calibrated adapters, global fronts and photographic quality remain unqualified.

[Eight-view sparse source checkpoint](eight-view-sparse-source-checkpoint/README.md)
adapts the existing production Kotlin pipeline test harness to the new images.
Its input-boundary and pure evidence-error helper controls pass, but the full
pipeline is not compiled or executed here. Native/runtime admission deliberately
refuses until a separately reviewed current-source capability is available.
This is not a successful eight-view reconstruction or a substitute pose solver.

[Immutable-ledger adapter R3](global-connectivity-adapter-r3/README.md) extends
the reconstructed controls to synthetic two-, four- and eight-camera ledgers,
with stricter pixel, flag, support-ID and attribute-domain validation. It retains
unknown-normal points and closed-boundary normal constraints. Original map/normal
estimator and reciprocal-witness regeneration are still not verified here;
production photographic binding and global front execution remain unqualified.

[Native pre-face observer](native-ledger-observer-r1/README.md) now validates
map, prepared-normal and ordered accumulator mechanics on a plane and a fixed
hole derivative from committed direct-depth controls. Independent matched builds
produce identical inputs, final fields and JSON/PLY/GLB output; all source IDs
are accounted for. The cameras coincide, and verified samples are constructor
inputs. This does not establish reciprocal witnesses, photographic reconstruction,
a full support certificate or any new meshing/front quality.

[Oracle-camera dense observer](oracle-dense-observer-r1/README.md) now records
actual native directed consistency attempts and pre-face evidence on all eight
fixed synthetic images, with camera poses supplied explicitly as component-test
inputs. Independent baseline/observer parity and sealed evaluation reproduce the
same poor mesh: 3,434 nonmanifold edges and incomplete intersection checking.
Only 33.8779% of observable reference-area samples are within a distance of
1% of the reference object's diagonal from the mesh. This is a measured diagnostic baseline, not image-derived registration,
a geometry improvement, a full support certificate or a global-front result.

[Native source-cell binding](source-cell-binding-r1/README.md) preserves
camera-local support before duplicate-face suppression. Independent replay matches
all 19 predecessor artifacts and all 42 compared outputs. The 15,726 derived
raw-corner support triangles include 2,531 duplicate-camera proposals, without
inventing their unavailable native winding. All observations and pre-face points
remain accounted for, including unknown normals. This is provenance and incidence
only: geometry, poor coverage and incomplete intersection checks are unchanged.

[Eight-view HOST CI implementation](eight-view-host-ci/IMPLEMENTATION_STATUS.md)
and its separate [runtime source successor](eight-view-sparse-runtime-successor/README.md)
are integrated with the new workflow deliberately unarmed. Independent and root
runs each pass 153 controller/capability guards; root also rechecks all 14 input
controls normally and optimized. These are process, integrity and synthetic
contract tests, not a compiled production pipeline or successful reconstruction.
The actual Gradle/Kotlin/native eight-view run remains NOT_RUN. Exact one-shot
source, parent, message and changed-path admission require a separate reviewed
arming change; ordinary device and sanitizer gates are unchanged.

[Eight-view HOST prerequisite failure](eight-view-host-c85-failure-evidence/README.md)
preserves the first actual hosted attempt at c85ff880, with zero production-pipeline
invocations. An owned Gradle transforms directory disappeared during bounded
inventory, and separate offline restoration failed because NDK configuration was
unavailable. The log's preferred NDK differs from the app's explicit pin; its cause
is not established. Source/license postchecks and owned cleanup passed. Exact
artifact and terminal-log receipt hashes agree. This is an infrastructure failure,
not a reconstruction result, and no retry or runtime qualification is claimed.

[Fixed-point global front R1](fixed-point-front-r1/README.md) implements bounded
empty-ball proposals, exact support certificates and online edge/vertex-link
checks without moving points or inventing unknown normals. Analytic controls pass,
but the whole eight-view attempt and independent replay both stop at the shared
5,000,001-visit work limit. All original points, observations and source triangles
remain accounted for. No candidate mesh or whole geometry evaluation was published;
this is a reproduced resource refusal, not an improved scanner or quality result.
A separately reviewed optimization may remove irrelevant-camera validation scans
without increasing the cap or weakening the certificate.

[Owned inventory race fix](eight-view-host-inventory-race/README.md) reproduces
the missing-directory mechanism with a synchronized real thread and replaces
path-only reopening with identity-bound, no-follow descriptor traversal. Only
vanished discovered descendants are tolerated; missing/replaced roots, symlinks,
permission failures and resource overruns remain fatal. All 165 guard tests pass
independently. Hosted corrected behavior remains untested; this change does not
rearm the workflow. Later official AGP source inspection establishes that the
missing-NDK error prints a hardcoded default revision even when resolution uses
the explicit app pin. It does not prove that the app's pin was ignored.

[Camera-partition front R2](fixed-point-front-camera-partition-r2/README.md)
removes irrelevant-camera index-validation scans while preserving all 192 ordered
query results, every source triangle and the original shared 5M work cap. The
charged saving is 2,626,042 visits, including partition construction. Analytic
controls and independent equivalence checks pass. Nevertheless, author and
independent whole attempts again stop at 5,000,001 support visits, now after
48,033 ball checks. No candidate mesh or quality evaluation is published. This
is a verified work reduction and reproduced resource refusal, not improved
scanner geometry or a justification for larger limits.

[Exact HOST NDK prerequisite](../EIGHT_VIEW_HOST_NDK_PREREQUISITE.md) adds bounded
admission of the app's pinned NDK 28.2.13676358 for Gradle configuration, using only
already accepted unchanged SDK licenses. The single direct official ZIP route
preserves all contained package links, verifies exact archive metadata and bounds
bytes, time, disk, ownership and cleanup. No SDK CMake or alternate NDK is added.
Independent 222 synthetic/controller guards pass, but no actual archive install
or revised app-pipeline run has occurred. This source change does not rearm the
one-shot workflow or change original device/sanitizer/quality gates.
