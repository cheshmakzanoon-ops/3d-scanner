# Exact planar ownership reference — QA only

This is NOT enabled in the Android scanner, NOT a repair of the measured noisy
mesh, and NOT SurfaceMesh-ready output. It is a small standard-library rational
reference for the next coherent-patch algorithm, not a replacement pipeline.

## Contract

Input is a list/tuple of at most 32 immutable source triangles with unique stable
string IDs of 1–128 characters. Record, triangle and point containers must be lists/tuples; coordinates
are built-in int/float or Fraction values (booleans and other types refuse).
Float coordinates are converted to their exact binary rational values. Every
triangle must be exactly coplanar and consistently oriented; a one-ULP fold,
parallel thin layer, opposing face, or degeneracy refuses the entire patch.
Source IDs define an **explicit lexicographic ownership policy**, independent of
container/camera iteration order. This is not a sensor-quality ranking, confidence
fusion, or lossless combination of conflicting attributes.

Subtract earlier owners from each source triangle with exact convex half-plane
clipping. Retained convex fragments are noded with all source and fragment boundary
vertices. Centroid fans preserve collinear boundary nodes. Each output face lies
entirely inside ONE immutable source triangle, with original-corner-order exact
barycentric weights at its three corners and `INTERPOLATED` status. All input geometry and IDs remain in the result. The API does not accept camera,
grid, color or other observation metadata; callers must preserve an external
immutable metadata association keyed by source ID, including disagreement records. No new sensor
support, confidence, UVs, or camera evidence is asserted.

Output positions remain Fractions. No snapping, approximate welding, float
serialization, near-planar flattening, or extrapolation is performed. Budgets,
cancellation, arithmetic invalidity, or unsupported input return `UNCHANGED`
with NO replacement faces. The caller retains its original input in that case.

## Bounds and limitations

Default caps: 32 source faces, 512 intermediate fragments, 2048 output faces,
4096 numerator/denominator bits at checked values, 200000 checked operations;
cancellation tested every 64 checks. Integer arithmetic temporaries may exceed
the checked 4096-bit representation, but operands are bounded at checkpoints.
This Python reference does **not** claim a hard allocator/time bound or mobile
runtime qualification. Caps are local reference safeguards, not changes to
production mesh/admission budgets. Limits must be positive non-boolean integers
no larger than the defaults. Input integer/rational bit lengths are checked
before conversion and geometry; malformed structures/numeric types refuse.
Cancellation callback/programmer exceptions propagate rather than being hidden. Exact rationals preserve microscopic slivers;
those slivers may not survive binary64 conversion. Such output is NOT approved
for publication as a float mesh. Arbitrary noisy or curved real patches remain
unsupported and unchanged.

## Tests and evidence

From the repository root, run
`python -m pytest -q tests/test_coherent_planar_owner_reference.py`. Tests independently implement rational convex
intersection plus inclusion/exclusion for small input unions, verify exact
union area, no positive-area output overlap, same-owner whole-face containment
via barycentrics, no edge T-junctions, manifold edge incidence and two boundary
loops around an actual hole. Include translated/copied/touching/disjoint patches,
2^-60 strips, collinear source nodes, attribute interpolation, input permutation,
axis rotations/scales, one-ULP folds, thin layers, opposing orientation, budgets
and cancellation. This is algorithm evidence on analytic fixtures, not real
object reconstruction quality or a formal proof of all inputs.

## Portable native / integration roadmap (not implemented)

1. Reproduce the production translated-plane nonconforming-overlap defect
   independently. A passing analytic reference does not fix the current mesher.
2. Port only the validated primitive to an opt-in native QA target. Candidate
   arithmetic is a pinned/reviewed Boost Software License header-only checked
   fixed-width multiprecision integer plus rational wrapper; cap every arithmetic
   construction and predicate, catch overflow and return unchanged. Alternatively
   constrain an explicit integer-coordinate admitted domain with proven bounds.
   A long-double epsilon predicate is NOT an exact replacement. No dependency is
   added by this reference; Android size/toolchain/license review remains open.
3. Before accepting rational-to-binary64 conversion, reconstruct exact rationals
   from the rounded binary64 output and independently recertify orientation,
   zero overlaps, conforming shared edges, source containment, complete support
   union, winding and nondegeneracy. Refuse if any condition fails, including
   collapsing slivers. An error enclosure by itself is insufficient. If exact
   support cannot be represented, retain original patch; do not silently move it.
4. Add explicit face-owner and corner-barycentric provenance to a versioned mesh
   representation. Preserve immutable camera/grid/depth source records and
   interpolated-vs-observed identity. Test colors and any attributes under the
   chosen owner policy; never invent observations or label interpolation as an
   ExactDuplicateFace relation. A triangle cannot mix three different owners.
5. Build texture adapters that use the same owner for every output fragment,
   retain source discontinuities/UV seams, and prove barycentric interpolation
   roundtrips through export/reopen. Migrate native/Kotlin checkpoint schemas and
   cache identities only after actual integration; reference changes no cache.
6. Capture bounded real patches with original camera/depth uncertainty evidence.
   An exact-planar primitive cannot reconcile near-planar production wedges.
   A separately reviewed uncertainty/displacement model must distinguish folds,
   thin surfaces and occlusion gaps before any approximate reconciliation. Then
   compare actual artifacts and real-stereo data, run sanitizers, cancellation/
   resource checks and Android qualification before enabling production.
