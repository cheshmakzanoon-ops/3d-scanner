# Exact cleanup provenance preservation

Synthetic, host-only correctness evidence. Baseline is
`c30e123df1d64414417a84d19807918a06cf6390`; this work does not repair general
mesh overlap, prove physical accuracy, or qualify Android resources.

## Root cause and precise semantics

`fuseDepthSurfaces` records one reference for every observation admitted to an
accumulator. Exact oriented-coordinate face cleanup removes duplicate faces;
normal/index compaction then used to erase every reference whose accumulator
vertex became unused. This is real loss of mesh-to-source identity, even when a
surviving vertex represents the same position. Original photographs, dense maps,
and the original `DenseResult::verifiedSamples` were not deleted. Native accepted
surface checkpoints already serialize that complete dense sample vector.

`sourceSampleId` is an index into the accompanying original verified-sample vector,
not a global image identifier. Camera index and pixel coordinates corroborate it.
The existing counters keep their original meanings:

- `inputSamples`: all provided verified samples, including ones rejected later.
- `acceptedSamples`: samples admitted before triangulation/cleanup, including
  accepted samples that never acquire a retained face.
- `fusedObservations`: admissions to an existing accumulator, not first admissions,
  not retained reference count, and not exact-duplicate support transfers.
- Vertex confidence is the maximum confidence of its accumulator contributors;
  color uses `max(.005, confidence) * supportingViews` weights. Vertex
  `supportingViews` counts distinct contributing source cameras, not every camera
  that verified a source depth sample or subsequently supplied duplicate support.
- `medianConfidence`, rejected-cell/triangle counts, positions, vertex normals,
  colors, faces and topology diagnostics are unchanged by this preservation step.

There was no existing JSON `observationCount` field. The binary observation count
continues to count **attached** references, never unattached ledger records.

## Narrow association proof, not new fusion

For each removed face, the new surface-only helper looks up its exact oriented
coordinate triple among retained faces. It canonicalizes cyclic corner order
without reversing winding and maps the corresponding corners. No distance or
normal threshold is added, no vertex is welded or moved, and no face is generated.
The ordinary deletion-only cleanup API remains unchanged.

An orphan reference with a unique such retained-corner correspondence is kept
with relation `ExactDuplicateFace`. Existing numerical contributors retain
relation `Accumulator`. Their roles are deliberately distinct: equivalent
geometric support did not retroactively generate the existing vertex's weighted
position, confidence or color. If an original vertex still has a face, its own
association wins; it is not moved to a different vertex. If two distinct surviving
vertices are candidates, no arbitrary winner is chosen.

Every accepted identity remains in exactly one of:

1. attached references (with one of the two explicit relations); or
2. `unattachedObservations`, with reason `NoRetainedFace` or
   `AmbiguousExactDuplicate` and no invented vertex association.

Identity duplication is rejected, not double-counted. Existing sample vectors
remain immutable. Exact *accumulator* coordinates are the proof: original sample
positions can differ from their weighted accumulator by roundoff. Equality of
original positions or nearest-neighbor proximity is not used.

Exact represented coordinates do not establish that physical sheets coincide.
Separations already rounded to identical doubles are not recoverable from those
coordinates; this change neither introduces nor fixes that pre-existing cleanup
limitation. Every representably distinct coordinate remains distinct here.

## Matched controls

These use the committed analytic direct-depth fixtures from
`../surface-diagonal-tie/controls.cpp`; they are not image-recovered geometry.

| Control | Baseline attached refs | Current attached refs | New exact-support refs | Unattached | Faces |
|---|---:|---:|---:|---:|---:|
| Rolled plane |514|578|64|0|512|
| Reversed camera order |514|578|64|0|512|
| Partial overlap |394|442|48|0|512|
| Depth step |484|578|94|0|480|
| Curved paraboloid |514|578|64|0|512|
| Strict non-tie slanted plane |514|578|64|0|512|
| Translated grid |578|578|0|0|632|

All original JSON geometry/attribute/topology values are equal across matched
baseline/current controls; only fusion identity and additive ledger diagnostics
change. The translated control still has 56 nonmanifold edges and 212 reported
intersections. This does not conceal or solve generic overlap.

The rotated control originally lost 64 references. Sixteen original source
positions did not equal a surviving vertex bit-for-bit (maximum displacement
1.11022e-16), but all 64 orphan accumulator corners have an exact duplicate-face
correspondence. The new relation records that specific proof, not an epsilon match.

## Texture implications

Current texturing does not read mesh observation references. Exposure balancing
uses vertex confidence; image selection uses geometry, depth visibility and face
`sourceCamera`. Legacy atlas selection gives that creating camera a provenance
bias and skips an extra centroid depth probe for it. Cleanup still retains only
one face `sourceCamera`; this patch is not a multi-source face-provenance model.
Confidence/color/normal attributes and that camera label are not changed.

On the migration fixture, baseline and current photographic texture JSON, PNG
and textured GLB are byte-for-byte identical. This is a regression control, not
an appearance quality improvement or proof for every scene.

## Serialization and cache boundaries

Surface JSON remains schema 8 because every existing field retains its semantics.
An optional **all-or-none** diagnostic group adds `observationLedgerVersion: 1`,
`attachedObservationCount`, `unattachedObservationCount` and
`exactDuplicateSupportCount`. The reopening validator accepts historical schema 8
without fabricating these fields. When any new field is present it checks the
version, strict bounded integers, `attached + unattached == accepted <= input`,
and `exactSupport <= attached`.

Native serialization changes from `RFMSH002`/version 2 to `RFMSH003`/version 3.
Attached records grow from 17 to 18 wire bytes (explicit relation); a separate
count prefixes 14-byte unattached records. Unknown enums, duplicates, mismatched
camera/pixel/sample IDs, unreconciled counts, overflow, truncation and mismatched
identities fail closed. Failed loading does not modify the caller's accepted data.

- Native filename: `surface-v3-fusion-v8.rfmesh`.
- Native digest domain:
  `realityforge-observed-open-surface-fusion-v8-checkpoint-v3-provenance-v1-topology-v2`.
- Kotlin surface policy: `rf-observed-open-mesh-v8-exact-cleanup-provenance`.
- Surface/descendant texture identities change. Sparse/depth identities do not.
- Original v2 files are not overwritten; the new canonical filename avoids an
  immutable old file blocking a new write under the unchanged depth directory.

The loader validates serialized binding/format/identity and checksums. It **does
not reconstruct removed-face correspondence** from the final mesh; the relation
is a producer assertion persisted under the new algorithm identity. Generic
mesh JSON/PLY/GLB exports are not complete source-reference ledgers.

The actual baseline-v2/current-v3 migration control records 88,783→90,453 bytes,
514→578 attached refs, 64 exact-support roles, zero changed depth bytes, two depth
references reused, zero depth writes, preserved old v2 bytes, and idempotent new
checkpoint replay. See `checkpoint-migration.cpp` (synthetic inputs).

## Validation and resource limits

Maintained native regressions cover exact cyclic duplicates, unchanged attributes,
signed zero and face-order changes, opposite winding, representable separations
(including a subnormal shift far below the scene-scale ULP), equal-position
unrelated/degenerate orphan observations, ambiguous two-target correspondence,
duplicate identities/pixels, stale plans, hard bounds and pre-/in-flight
cancellation. Checkpoint tests include checksum-recomputed unknown role/reason,
repeated and cross-ledger identities, overflow and old version rejection.
Existing fusion tests count only `Accumulator` references when assessing fusion.

The helper admits at most 40,000 vertices, 80,000 faces and 100,000 identities.
Exact-key maps and repeated cleanup validation add bounded work and transient
memory. Identity validation uses a 100,000-byte ID array and at most 800,000 bytes
of pixel keys rather than two large node-based sets. Cancellation is checked
through loops and sorting. Exceptions return no partial ledger or completed file.
The existing 10 MiB **retained geometry** cache cap and 12 MiB file cap remain;
these are not whole-operation/transient-RAM caps. The native reference struct
in this host ABI grows from 24 to 32 bytes, so some large valid geometry may skip
the optional cache earlier while remaining usable.

A three-run near-capacity host microbenchmark (`resource-benchmark.cpp`) with
40,000 vertices, 79,202 nonduplicate faces and 100,000 refs measured:

| Cleanup path | Elapsed time | Process peak RSS |
|---|---:|---:|
| Baseline |21.85–24.59 ms|17,648–17,720 KiB|
| Preservation |50.53–54.56 ms|23,532–23,552 KiB|

This is neither an exhaustive worst case nor ARM64/device qualification.
Resource parity is **not** claimed.

Validation receipts for the isolated candidate:

- Fresh Release `-Werror` CMake build and 48 CTest targets: passed.
- Portable Kotlin 2.1.21/JVM17: 9 tests passed, using real production classes,
  including actual native JSON reopening, legacy schema 8, malformed ledger
  diagnostics, parent/descendant identities and reference filename selection.
  This is not the full Gradle/JVM/instrumentation suite.
- Local pytest excludes both real PyPI audit cases under the current network
  authorization; excluded external audit gates are not claimed passed by that
  run. An earlier separate run reported 1,752 passed, 3 skipped and 11 subtests,
  but its external-audit network/data provenance was not captured and is not a
  current remote-audit qualification.
- Android Gradle compilation/lint, instrumentation, physical scanning and mobile
  memory/thermal performance: **NOT_RUN**, not established by these host results.

For reproducing the diagnostic programs, compile the same `.cpp` first against
baseline headers/library, then against current headers/library with
`-DRF_PROVENANCE_V3`. Run the baseline migration writer and current migration
reader with the same fresh scratch cache root and separate output prefixes.
The migration driver requires two arguments: an existing absolute scratch root
and an absolute output prefix whose parent already exists. Baseline mode requires
an empty root; it marks only a successfully completed synthetic fixture. Candidate
mode requires that marker, its SHA-256 binding to all three baseline files, and
the exact baseline depth/checkpoint layout (plus an
optional existing current checkpoint for replay). Unexpected entries, symlinks,
dot path components and output prefixes inside the cache root are rejected.
Never point it at a user's actual project cache.

All four output targets must be absent before any cache mutation, including
symlinks and directories. Each run also reserves a new `<output-prefix>-staging`
directory; it writes there, then copies with no-replace semantics, so publication
cannot overwrite an output created after preflight. No existing file is deleted.
The staging directory is retained as evidence, including after a failed run; use a
new prefix rather than reusing it. Baseline reruns need a new empty scratch root;
candidate replay uses the marked root with a new output prefix. The scratch root
must not be concurrently modified by another process. Failed runs may leave their
own partial scratch/staged/new output files, never a completed baseline marker.
The resource driver needs no arguments and only allocates synthetic in-memory
meshes.
