# Extending an established sparse landmark

The initial anchor-selection/registration loop remains unchanged. The later bounded
registration frontier distinguishes two requirements:

1. Establish world XYZ using at least two distinct registered source observations.
   Native triangulation and the independent positive-depth/reprojection support
   filter remain required; the candidate-row boundary also rejects groups with
   fewer than two source observations.
2. Propose a new image observation through at least one reciprocal descriptor link
   to that already-established landmark. Missing another descriptor edge is not a
   contradiction. Any differing candidate-key destinations reject that landmark;
   competing landmarks claiming one candidate key are all rejected, not first-wins.

These are tentative 3D–2D correspondences, not accepted camera poses. The minimum
16 inputs, native robust PnP, minimum unique inliers/inlier fraction, rigid-pose
checks, positive depth, independent reprojection checks and thresholds are
unchanged. Cancellation still propagates. No new triangulation from one view,
majority-vote conflict resolution, descriptor thresholds or hidden pair matching
are introduced. The frontier still consumes the persisted admitted pair schedule.

`IncrementalRegistration.POLICY` versions both frontier input identity and the
production sparse settings. Old frontier payload identities are rejected; changing
the sparse stage invalidates alignment and descendants, not valid feature/matching
artifacts. Renaming an old stage checkpoint cannot rebind its embedded identity.
The unchanged checksummed wire format continues to describe source identities;
it is not itself proof of geometric acceptance.

Tests execute the real Kotlin frontier and durable admitted-session wrapper with
analytic triangulation/PnP callbacks, including a three-image chain with exactly
two registered anchors and no closing descriptor edge. Negative fixtures preserve
source-support, conflict, duplicate-target, wrong-pose, insufficient-inlier,
cancellation and stale-cache rejection. They are not JNI accuracy, actual-image
registration, Android execution or full reconstruction qualification.
