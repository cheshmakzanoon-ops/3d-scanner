# Unfinished confirmed-observation provenance design — saved after user stop

This is WIP design documentation, not an implemented or tested API. Development and tests were stopped by the user before any provenance header, test or DenseResult integration code was authored. Existing production behavior remains unchanged. Frozen source `2f18a4f5a87139c38ee388f6c70af33e0fb247f7` and its native evidence do not qualify this unimplemented extension.

Agent049 instruction2 requires retaining source observation identities and bounded support sets. Current VerifiedDepthSample retains its origin camera/x/y and support counts, but not the actual confirming peer camera/cell identities. DenseResult.sourceViews records allowed adjacency, not per-sample confirmations.

Proposed contract, not finalized:

- Keep VerifiedDepthSample layout, all existing acceptance decisions, pixel/depth/point outputs, thresholds and persistent cache keys/formats unchanged. Append a sidecar vector to DenseResult aligned one-for-one with retained verifiedSamples.
- Origin identity is input camera ordinal plus row-major grid cell. Camera ordinals refer to the ordered DenseResult.maps/input views, never a source-plan slot or a global photo ID. Callers retain the stable selected-photo-ID mapping.
- Each record retains at most3 actually confirmed peer cameras. Each peer retains at most4 source grid cell IDs and the actual double interpolation weights. Record only contributors used by the existing sampler (weights>=1e-12); nearest baseline records its exact rounded grid cell with weight1.
- Append a peer only after depth, reverse-reprojection and optional normal rejection gates pass. Unknown/rejected candidates are absent; unknown normals remain explicitly depth-only and a supported-normal flag is true only after actual independent normal verification.
- Reconstruct sidecars from accepted maps on every clean/resumed run. Do not promote adjacency or an estimated normal to confirmed evidence; no new association or depth decision is proposed. Existing053/057 C++ callers can access the sidecar using sourceSampleId; their visibility algorithms are not replaced.

Proposed POD layout, not compiled or ABI-verified: a peer with double weights[4], uint32 cellIds[4], uint8 camera, uint8 contributorCount and bool normalSupported is expected to occupy56 bytes on the current64-bit ABI; three peers plus uint32 originCell and uint8 originCamera/peerCount are expected176 bytes. At100000 records the proposed sidecar alone would be17,600,000 bytes (about16.78MiB), excluding its vector control object. These are design estimates, not current allocations or a measured resource result. Exact sizeof/alignment and all target ABIs still require validation.

Memory plan: calculate the admitted grid-sample upper bound before allocation, reserve exactly that capacity for both verified samples and sidecar, avoid40k-to160k vector growth, retain the existing conservative admission floor and add checked provenance capacity bytes, including coarse recursion. Never raise workload defaults or claim a64MiB bound without that implementation and evidence.

Unresolved API placement: an independent049-owned POD header or an explicitly coordinated Dense model declaration is needed to avoid the existing dense.hpp/depth_consistency.hpp include cycle. No such header/API has been created.

Required future checks, all NOT_RUN for this extension: independent contributor-ID/weight fixtures; overflow and invalid/zero contributor bounds; rejected-peer exclusion; origin/local-camera mapping; normal-supported versus unknown distinction; producer alignment with actual accepted samples; camera permutations, tile sizes and clean/resumed equivalence. Because appending DenseResult changes its internal C++ return/layout ABI, rebuild all consumers and compare old-header/old-b95 and new-header/new-library consumers separately. Never call a new DenseResult header against the old library. Compare maps, points, old VerifiedDepthSample fields and accepted counts in baseline/default and strict/refined profiles. Then rerun required host/native/Android qualification.

User stop disposition: preserve this design and current commits; no implementation or tests continued after the stop.
