# Measured topology qualification at inspection and export

Base: `efb0f748a45d7e7523beec336e185e86ea303ff7`
(tree `b6bb2f60cb485db44ec8ffce5a3c51467d62bfd4`).

## Scope

This is a presentation/evidence-propagation change, not a meshing repair or a
quality-threshold change. It preserves all mesh vertices, faces, observations,
export bytes, formats and eligibility. A legal indexed-mesh export is not a
manifoldness, watertightness, printability or physical-accuracy certificate.

The native mesh reports `nonmanifoldEdges`, `intersections` and
`topologyIncomplete`. Existing `ScanQualityReport` code validates the native and
legacy aliases and persists canonical topology evidence. Previously the typed
reopening DTO discarded that evidence, and neither Studio nor export displayed
measured counts. The workspace displayed generic limitations and a capped list
of recommendations, but no measured topology warning.

## Data and display contract

- A small immutable `SurfaceTopologySummary` is derived only from the canonical
  result of the existing diagnostic-report validator. It travels with the exact
  reopened artifact key/revision through prepared viewer geometry. A historical
  preview with no diagnostic report remains readable and reports unknown
  topology; raw optional metadata does not become a new geometry acceptance gate.
- Workspace current/previous surfaces, reopened Studio and surface export choices
  use the same qualification display, independently of the four-recommendation
  cap. Point-only, atlas and diagnostic choices do not acquire a surface warning.
- Positive counters are measured defects. Incomplete intersection checks display
  the count as a lower bound. Missing counters and absent completeness flags stay
  unknown. Even complete measured zeros do not certify manifoldness or a closed
  surface, and say nothing about unobserved physical geometry.
- Export qualification is bound to the full existing `ArtifactIdentity` and read
  through typed reopening, with current-identity checks before and after. A
  failed optional qualification read yields unknown information rather than
  removing otherwise valid export choices. The export view fences its displayed
  catalog against the selected project/revision/result key immediately, including
  the interval before a restarted asynchronous load runs. Cancellation propagates.
- `SURFACE_TOPOLOGY_DEFECTS` is an advisory diagnostic recommendation derived from
  positive measured counters for a surface. Normalization re-derives it rather
  than trusting supplied text; schema-1 migration cannot retain it because that
  schema has no topology evidence. Report/schema versions and geometry statuses
  are unchanged. Mesh count labels say “indexed triangles,” not “verified
  triangles.”

## Focused verification

Using the project's pinned Kotlin 2.1.21 compiler, JUnit 4.13.2, coroutines and
org.json artifacts, with hashes checked against `gradle/verification-metadata.xml`:

- **45 Kotlin/JVM tests passed**: 16 new qualification/identity/reopen tests and
  29 existing quality-report, coverage, topology-report, prepared-geometry and
  bounded-UI-projection tests. These compile the real pure-production source
  dependency closure, without mocked replacement implementations.
- **4 source wiring tests passed**: shared workspace/Studio/export display,
  surface-only scope, advisory reads, current-selection fencing, cancellation and
  accurate triangle-count wording. These are not rendered UI tests.

The new tests also cover native/legacy aliases, null versus zero, partial and
incomplete audits, unknown historical reports, wrong diagnostic identity,
recommendation truncation, repeated report normalization, previous-result export
rejection, point-only scope and unchanged export options.

Android/Compose compilation, lint, instrumentation and visual/device execution
are **NOT_RUN** for this candidate in the local executor. Focused JVM compilation
and source checks do not replace these gates. No handset or physical-object
quality qualification follows from this change.

## Separate native counterexample

A fresh build of the unchanged existing `surface_test.cpp` at the base tree passed
all 11 native cases. Its rendered synthetic three-camera plane travels through
actual dense reconstruction, surface fusion and GLB/PLY export. Independent export
parsing found 3,957 vertices, 8,559 faces and 509 nonmanifold indexed edges (maximum
incidence four). This is a numerical counterexample, not a physical capture or
proof of whole-object quality. It remains unresolved by the display change; no
faces or observations were removed to improve that count.
