# Bounded topology admission for observed-source welds

Surface fusion v12 adds a conservative topology condition to existing geometric
fusion. It does not change dense/refinement defaults, normal estimation, matching,
visibility, metric/angle thresholds or camera-grid face gates. Refinement remains
OFF in the compatibility profile. No clipping, interpolation, invented depth,
hole filling, keep-first-two-face rule or intersection suppression is introduced.

## Admission and rollback

The existing fusion pass proposes groups with at most one measured source pixel
per camera. Existing camera-grid triangles and exact duplicate handling are
formed first. For each retained fused vertex, the oriented link must be one
connected path or cycle, with no repeated directed adjacency or edge incidence
above two. The bounded checker ignores only independently proved exact face
cleanup defects, using the same cleanup policy as final output.

At most three triangulation passes run:
1. Initial proposed fusion and topology admission.
2. Roll back groups whose fused stars fail admission; triangulate again.
3. If any retained fused star still fails, roll back all remaining welds and
   triangulate once more. This deliberately conservative fallback can undo safe
   groups too; it is not an assertion that every rejected group caused a defect.

Rollback restores each contributing source's exact original measured XYZ, color,
confidence and source identity, not copied averaged positions. The source-grid
index and observation ledger are rebound and the unchanged face gates are
reapplied. Restoring all sources must fit the existing vertex cap, otherwise the
operation fails closed. Existing confidence/vertex-admission omissions are not
retrospectively filled. Exact duplicate cleanup remains the sole cleanup deletion
policy. Regeneration can recover faces previously rejected at averaged positions;
no unobserved triangle is created.

The policy refuses a fused star that was already invalid before identification;
it does not prove causal attribution. Unrelated single-camera topology defects
are not deleted or concealed. The existing final topology report, plus
`unresolvedSourceVertexLinks`, retains them. Noncoplanar overlaps/intersections
remain measured limitations: a manifold edge/link is not an ownership or physical
surface certificate. This change does not integrate the OFF exact-planar owner.

## Bounds and cancellation

Hard limits remain 100,000 verified samples, 40,000 vertices and 80,000 faces;
per-source star storage is fixed at 64 incident faces and 128 link neighbors.
Oversized stars, malformed retained-face lists or restored-vertex exhaustion
fail closed, never truncate evidence. Cancellation is checked during incidence
construction, per-vertex checking, restoration and triangulation.

Normal preparation and spatial buckets are released after fusion, before topology
inspection. The checker uses one 32-bit next pointer per triangle corner and one
head per vertex, not dynamic per-vertex maps. Its buffers, exact-cleanup index
capacities, rollback flags and fixed local scratch are explicitly admitted within
reclaimed preparation bytes plus 32 KiB of the existing 64 KiB fixed reserve.
No default working-set budget is raised. The ordinary final cleanup/topology
analysis retains its existing separate lifetime and bounds.

## Diagnostic meaning

`mesh.lossDiagnostics.topologyAdmission` is originating-call evidence only:
- `passes`: completed triangulation/admission passes, at most three.
- `proposedWeldGroups`: groups with multiple sources before topology admission.
- `rolledBackWeldGroups`: groups eventually restored, including fallback groups.
- `restoredSourceVertices`: all measured members restored, not net added vertices.
- `unresolvedSourceVertexLinks`: final topology's remaining invalid links,
  including unrelated source-grid defects; not silently zeroed.

Existing fusion-candidate first-gate counters describe provisional geometric
candidate visits. They are not final retained fusion counts. `fusedObservations`
reflects retained groups after rollback. Cell/triangle-attempt diagnostics describe
only the final triangulation pass. Cached surface records omit originating
counters and remain explicitly unavailable, not zero.

## Identity and validation scope

Surface engine/checkpoint identity advances to v12, including its digest domain
and filename. Old v11 surface geometry and texture/export descendants invalidate;
verified depth maps, sparse geometry and earlier stages keep their own identities.
Historical files remain intact. The checkpoint wire layout remains v3.

Synthetic tests cover four-face shared edges, bowtie links, legitimate seams,
exact duplicate patches, observation provenance, reordering, cancellation and
restoration limits. Controlled local-refinement replay is a negative regression
only, not a new production setting or HOST/physical-quality pass. Final validation
results are recorded separately; no Android/device/release qualification follows
from this policy alone.
