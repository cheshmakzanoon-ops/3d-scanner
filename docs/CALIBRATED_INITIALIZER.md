# Calibrated two-view initialization

The additive `reconstructCalibrated` route receives undistorted normalized rays,
actual observed working-image pixels in the SAME match order, and two explicit
Brown-Conrady domains (`fx,fy,cx,cy,k1,k2,p1,p2,k3,width,height`). This is not a
claim that every production lens profile is measured: the app may use its existing
estimated pinhole calibration. K and dimensions come from each open FeatureFrame,
not a fixture/default camera. Existing normalized-only C++/C/JNI reconstruction
interfaces retain the essential-only behavior; no inferred FOV is substituted.

Spatial admission uses observed pixels, not K times undistorted rays. Inputs must
be finite, bounded and aligned; forward-distorted rays must agree with supplied
pixels within0.01working pixels. Native code validates before expensive fitting.
The app uses firstKeys/secondKeys to pack actual Frame.xy, preserving row ordering
through canonical matcher ordering and cached match reuse. Bounds/misalignment
errors return INVALID_INPUT; cancellation and resource exhaustion remain operational
failures, not optional pair rejections.

The native branch uses bounded normalized DLT homography RANSAC/refit and calibrated
decomposition. Conservative admission thresholds remain >90%model support, >90%reconstructed model
inliers, >=50points, robust median parallax>=1degree, runner-up<75%, >=4observed cells
per image and covariance det/trace²>=1e-4. Caller requirements may make these stricter.
Settings/seed/threshold are honored. H and unchanged E reserve a combined50M
model-match consensus-pass envelope including6refit passes, not an arithmetic or
walltime guarantee. Saturated budgets with no H capacity fail before work rather
than silently reducing E trials or skipping planar analysis. H progress is Sparse
reference0/tile1; legacy E remains tile0. Cancellation checkpoints cover input,
RANSAC/refit and triangulation work.

**Conservative routing:** once selected H support is dominant, losing dominance
during refit returns PLANAR_AMBIGUITY. A constructed regression starts219/240 and
refits210/240; it must never fall back to E. The first400Gaussian-noise branch-search
attempts did not establish this case; a separately declared two-motion construction
found it on its first attempt. These are branch controls, not held-out accuracy data.

Calibrated JSON includes `initializerModel` (HOMOGRAPHY or ESSENTIAL), `modelInliers`
and the corresponding `homographyInliers` or `epipolarInliers`. Legacy generic
`inliers` remains for consumers. Legacy-only JSON field layout is unchanged; duplicated support now collapses. The old C++ member
name epipolarInliers is retained for source compatibility but is explicitly qualified
by initializerModel on calibrated results. The sparse policy identity increments;
features/matches are reusable, alignment and geometry descendants invalidate.

## Exact distinct-pair support policy

Both initializer routes validate every raw row and enforce raw count/work limits
before collapsing exact `(ax, ay, bx, by)` normalized-coordinate duplicates. `-0`
and `+0` are equal; nearby values and one-to-many observations are not merged.
Color and observed-pixel metadata do not create additional geometric support.
The first occurrence supplies metadata and its **original input row ID** is
published, preserving SparsePipeline track/observation lookup. `inputMatches`
remains the raw admitted count; model inliers and points count distinct pairs.
Distinct pairs are not proof of statistically independent support.

Essential/homography sampling, model selection, refits, ratios and point support
all use this same distinct set. Existing numerical, spatial, baseline and pose
gates are unchanged. Raw-count homography iteration caps are retained: collapsing
rows cannot buy additional hypotheses beyond the admitted combined budget.

Policy `calibrated-h-observed-pixels-sticky-dominance-distinct-pairs-v2` invalidates
prior sparse/alignment identities, frontier checkpoints and downstream geometry.
Feature/match caches retain their own identity/integrity checks, but reused matches
must pass the new initializer again; old geometry cannot become current by reuse.
This does not address weak spatial/pose conditioning or establish three-view
registration, skull reconstruction quality, device behavior or release readiness.

## Validation and limits

The retained anchor fixture contains 194 raw rows but 162 distinct normalized
pairs. Under the new policy it yields 159 homography-supported points, not the
historical 191 rows. Regression tests verify first-original-row IDs and metadata;
this is initializer-only replay, not a new downstream/three-camera validation.
A fixed 78-case synthetic audit changes only the 12 cases replicating 12 distinct
pairs three or fifteen times: all now reject. The other 66 case records are
unchanged, including demonstrated poor-conditioning limitations.

Host tests cover invalid settings/metadata, actual versus virtual distorted grid
membership, stable original indices/colors, pre/in-flight cancellation, shared work
limits, model fields, loss-of-dominance rejection, genuine320/320ambiguity and
nonplanar IEEE754 output equality to the legacy solver. An Android instrumented
contract test covers the new JNI route, shape checks and operation cancellation;
source inclusion/host syntax checks are NOT device execution.

Historical pre-distinct-pair replay (not validation of the new policy): original384x288first3photos,194exact re-extracted
matches, actual observed pixels and K480. The app threshold1.5/min(focal)=.003125 is
unchanged. The prior initializer yielded the EXACT frozenV2 191point identities,
poses and geometry; original frozen downstream PnP/PoseAndBaseline BA also matches
exactly (191points,10steps). Holding downstream code constant isolates API plumbing,
not whole-app parity. Earlier geometry evaluation showed improved poses/partial
coverage but thousands of nonmanifold edges/intersections. **This is not evidence of
high-quality complete-object meshes, metric scale, Android runtime success or release
readiness.** No truth poses/points enter selection or sparse/dense bounds.

Mathematical decomposition adapts official OpenCV4.12 homography_decomp.cpp with its
complete SamsonYilma/Intel BSD notice retained in source and indexed release/debug
assets. There is no native OpenCV linkage and no copied GPL ORB-SLAM implementation.
See THIRD_PARTY_NOTICES.md and native-source-notices.json for provenance. First-party
distribution-license decisions remain separate and unresolved by this change.
