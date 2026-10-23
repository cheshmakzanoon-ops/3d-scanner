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
