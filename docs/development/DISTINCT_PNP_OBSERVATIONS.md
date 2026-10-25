# Exact PnP observation support

Raw PnP buffers retain the existing 16–5000-row and finite-coordinate/threshold
limits. Bounds and validation are applied before exact tuple deduplication, with
cancellation checks before and during the bounded preprocessing. Invalid rows
cannot disappear behind valid duplicates.

The full finite `(X,Y,Z,normalizedU,normalizedV)` tuple defines exact equality.
Numeric positive and negative zero are equal. No tolerance, quantization, spatial
clustering or digest-only identity is used. Same XYZ with different UV (or vice
versa) remains distinct evidence for the existing robust solver to assess.

Stable first occurrences feed unchanged DLT/planar initialization, RANSAC,
refinement, minimum16 and28% consensus rules. Repetitions cannot inflate the
number of hypotheses' available distinct observations or consensus votes. Final
inlier indices map to the first ORIGINAL input row; `inputTracks` remains the raw
buffer row count and the JSON schema is unchanged. Exact distinct tuples do not
prove statistical independence or eliminate near-duplicate/degenerate geometry.

The Kotlin frontier first rejects every competing landmark claiming the same
candidate feature ID, then deduplicates exact geometric tuples in first-seen order.
This ordering prevents a repeated geometric row from hiding an identity conflict.
It aligns the frontier's unchanged independent16/28% pose checks with the native
solver rather than using a duplicate-inflated denominator. The separate initial
anchor registration loop is unchanged and receives the native guard directly.

`IncrementalRegistration.PNP_POLICY` binds both durable frontier input identity and
production sparse-stage identity. Feature/matching settings remain unchanged;
alignment and descendants must recompute. When combined with established-landmark
single-link admission, BOTH policy tokens must remain in the frontier identity,
and production sparse settings must include both policies. The separate
single-link patch's source-group>=2 requirement is also retained in the union.

Evidence includes actual C++/CAPI planar and nonplanar tests, signed zero, original
row mapping, insufficient distinct support, distinct nextafter tuples, conflicting
UV and invalid/oversized raw rows. Actual Kotlin tests cover frontend ordering,
distinct-count admission and cache identities using analytic solver callbacks.
Those callbacks are not JNI accuracy or actual-photo/full-scanner qualification.
