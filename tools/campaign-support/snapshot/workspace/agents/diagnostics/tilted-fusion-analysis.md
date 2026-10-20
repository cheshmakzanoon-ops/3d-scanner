# Independent calibrated surface-fusion geometry

No repository files changed. Counterexample executable compiles the current actual normal/visibility headers and real core/cancellation sources. It uses no held-out photographs or thresholds from their results.

## Counterexample

Two identity-rotation cameras with `f_x=f_y=200`, principal point `(63,63)`, centers `(0,0,0)` and `(.03,0,0)`, stride3, observe the exact plane `z+tan(70deg)*x=4`. Their central source samples are `P=(0,0,4)` and `Q=(.03,0,3.9175755977630615)` (actual float depth map). Both source pixels are admitted grid centers `(63,63)`. All nine neighborhood depths are real samples of the same plane, confidence.8. Actual helpers report:

- supported normals, conditioning.1169116/.1169118, same oriented direction;
- independently sampled supported visibility in both cameras, relative errors.00040748/.00044264, versus existing.08 tolerance;
- exact dual projected distances1.53155947 and1.5 pixels, less than unchanged `.6*stride=1.8`;
- world distance.0877142069, greater than BOTH flat-footprint radii.036 and.0352581804;
- normal displacements only1.45e-8 and4.21e-8 (float sampling residual).

This failure is a camera projection/foreshortening fact: equal angular resolution corresponds to a longer displacement along a tilted surface. There is no depth-layer, confidence, support, discontinuity, or unknown-normal relaxation involved.

## Exact acceptance metric

Retain every existing support, confidence, visibility, positive-depth, same-camera exclusion, normal-orientation, and resource gate. Unknown normals still cannot authorize expanded association. Let `delta=Q-P`, `T=.6*stride`, `R=min(flatRadiusP,flatRadiusQ)` and `nP,nQ` be supported unit world normals. For BOTH observed cameras, require:

`q_i = ||project_i(Q)-project_i(P)||^2/T^2 + (dot(n_i,delta)/R)^2 < 1`.

Equivalently use the maximum of the two `q_i`. The example gives.72397358/.69444444. The summed squares avoid granting both a full tangential budget and full normal budget at once. Fronto-parallel, on-axis, first-order isotropic calibration recovers the original sphere. Normal separation never exceeds the original unchanged `R`: parallel layers separated in their normal direction by at leastR remain rejected even if their projections coincide. Layers closer thanR were already inside the old normal tolerance; claiming absolute separation for arbitrary sub-R layers would be false. Preserve independent visibility/depth and compatible supported normals in all cases.

For an accumulator, use stored ORIGINAL source camera/pixel/position/normal evidence (bounded<=8 cameras), not only a drifting average's projection. Validate against all retained observations, and revalidate the prospective weighted centroid before committing if summed-square constraints are used. Do not claim a synthetic centroid is an additional observed source.

## Finite exact broad-phase bound

In one camera, let original point `P=Z*d`, where `d=(X/Z,Y/Z,1)`, and unit camera normal `n`. Let `E` be the3x2 matrix with columns `(1/fx,0,0)` and `(0,1/fy,0)`, `h=n.d`, and `b=E^T*n`. For a displacement with exact pixel shift `u` and normal gap `eta`, we have the exact identity:

`Q-P = [ Z*(h*E - d*b^T)*u + eta*(d+E*u) ] / (h+b.u)`.

Thus for `||u||<=T`, `|eta|<=R`, and `D=|h|-T*||b||>0`:

`||Q-P|| <= [ Z*T*||h*E-d*b^T||_2 + R*(||d||+T/min(fx,fy)) ] / D`.

This matches051's proposed formula. A Frobenius norm may replace spectral norm in the BROAD PHASE only; it is a safe overbound, with no acceptance relaxation. A local Jacobian alone is not an exact finite-pixel bound because its denominator ignores the ray/plane horizon.

Guard `D<=0` or numerically unstable/nonfinite computation explicitly. Either use the existing absolute world association cap600 as a broad-phase bound and retain the exact metric+all gates, OR conservatively decline expansion and require the original flat-radius world-distance bound for that pair. A denominator clamp pretending a finite plane bound exists is unsound. The first option is complete within the already-existing600 cap; the second may reject valid grazing pairs but creates no false geometric evidence. Use scaled/long-double intermediates and cap before narrowing to double.

If buckets are centered on a weighted centroid rather than originalP, account for that offset: an original-source boundB gives centroid-centered search radius at least `B+||centroid-P||`. An unadjusted bound about a different center can miss valid pairs. Maintain bounded occupancy/candidate visits and move buckets when centroids move, as in the local-radius draft.

## Root's frozen v5 predicate (before real-graph measurement)

For each original anchorPi and incoming observedQ, use shared `R=min(Ri,Rq)` and `N=max(abs(ni.delta),abs(nq.delta))/R`. Both exact original-pixel residuals must satisfy `N^2+Ei^2<=1` and `N^2+Eq^2<=1`. Preserve both-direction observed depth/visibility, supported compatible normals, confidence and resource checks. The independent counterexample also satisfies this stronger shared-N predicate. Under inclusive<=, layers separated in their normal direction by MORE THANR always reject; exactlyR is the boundary and can pass only with zero pixel residuals.

The original-pixel square cone plus original normal slab is convex. With all four corner ray denominators strictly sharing a sign and both plane/slab numerators sharing that sign, its positive-depth intersection has exactly eight vertices. Maximum Euclidean distance to the original point is bounded by the maximum of the eight vertex distances, by convexity of norm. All corner signs/intersections must be validated before capping the computed bound. Mixed signs/near-horizon/nonfinite or nonpositive intersections conservatively disable the expanded association; no invented finite denominator clamp.

Store at most eight original source IDs per accumulator. LetBi be each original's bounded enclosure radius. Explicit pair gates include `distance(Pi,Q)<=min(Bi,Bq,600)`. Index a weighted centroidA with `min(600,min_i(Bi+distance(A,Pi)))`, rounded upward to the spatial level. Every retained Pi lies in the incomingQ convex cone/slab/ball, so the positive-weight averageA also lies there and `distance(A,Q)<=Bq`. The triangle inequality supplies the indexed radius independently. Thus a27-cell search at each occupied level is complete for accepted pairs. Moving centroids must be rebucketed. The exact summed-square metric is a stricter nonlinear subset; explicitly revalidate a proposed centroid against original observed constraints rather than relying on convexity of that subset.
