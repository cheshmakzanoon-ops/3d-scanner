# Rejected experimental canonical projective field — fixed snapshot v2

**Decision: reject as a replacement/default. No production change or parameter tuning.** The bounded replay and independent witness checks succeeded; that is execution evidence, not quality acceptance.

| Same frozen synthetic truth | Original | Experimental candidate |
|---|---:|---:|
| Truth-image covered pixels / 294,723 | 137,979 | 3,671 |
| Projected truth coverage | 46.816502% | 1.245576% |
| Indexed nonmanifold vertices | 369 | 107 |
| Nonmanifold edges | 166 | 0 |
| Inconsistent winding edges | 0 | 0 |
| Connected components | 177 | 521 |
| Boundary edges | 4,873 | 2,668 |
| External intersection check | NOT_RUN_BUDGET | 0 pairs, check executed |

134,308 originally covered truth pixels were lost; none gained. On the 3,671 pixels covered by both outputs, p95 absolute depth residual improves from 0.04154093 to 0.03109822 synthetic units (3,134 improved, 537 worsened). This selected common support cannot offset or hide coverage collapse. Coverage is image-space support, not a guarantee of 3D completeness; different-area surface sample errors are not paired comparisons.

Zero bad edges does not mean manifold topology. The unchanged c4b0ca8 metrics audit each indexed vertex's incident triangle link: disconnected/malformed links expose 107 candidate vertex defects even when individual edges have at most two incident faces. No welding or coordinate merging is applied. Candidate is open/defective, not watertight.

The external checker keeps its existing 5,000-face budget: original 6,741 faces are **unknown/NOT_RUN_BUDGET**, while candidate 1,514 faces are checked. Original native **6,190** crossings belong to a separate existing native audit and must not be compared to candidate external zero as equivalent methods. Candidate native audit is unavailable.

## What was actually tested

Same immutable snapshot d1b605dffddb9488e77ff2f16706f3cf0f2d4161e3239d03f51648d5290b71c0, depth identity 9fb072db88498574d07b6125101f2454a1d4343b209944fe8de983b17f0f0995, original StageC replay, source maps/verified samples/poses, truth and capture gauge. New interpolated vertices are explicitly experimental, not original verified observations. This is a canonical perspective-correct camera-z projective field, not the previously rejected oriented-patch field and not Euclidean distance.

Pitch 0.07802324859719528 and truncation 0.15604649719439057 were fixed before replay. Once-per-camera integration, admitted observed-source masks, bounded sparse allocation, shared six-tetra extraction, and single-patch whole-face proximity witnesses remain conservative. Unknown space is not filled. Exact-zero degeneracy fails closed; thin-layer separation and general grid invariance are not guaranteed. Whole-face witness is bounded proximity, not exact source-plane coincidence.

New-algorithm counters record 16,220 visited cells: 14,609 missing-corner cells and 1,611 valid cells; 7,008 face candidates yield 5,494 witness rejections and 1,514 accepted faces. These identify rejection stages, **not a proven causal decomposition of pixel loss**, and are not attribution to the old oriented-plane v4. No threshold loosening or follow-up candidate was attempted.

Synthetic guards preceded actual replay. Two actual replays were byte-identical, including counters; independent witness verification checked all 1,514 faces against 6,782 admitted patches. Replay total 9.0651s includes compilation, tests, two replays and two verifiers, not candidate runtime. Workspace 28,072,144 + 65,536 stack allowance bytes is bounded scratch, not process RSS and excludes immutable inputs/Python verification. Replay bounds: aggregate 300s, child 2GiB address space, file 32MiB; separate verifier 30s. Measurement completed once in 4.1665s under 60s/1GiB/single-thread limits; all 622 measured pins unchanged. No Android/full-controller/shipping performance qualification.

## Contents and historical reproducibility

- `prototype/`: exact reviewed v2 header, replay/tests, runner, independent verifier, config, recipe and conservative allocation proof. Source freeze header 2fb31919, runner 544c3515, config 1469331c. Prototype v1 was source-only and replaced after static allocation review; never executed.
- `measurement-source/`: exact reviewed measurement adapter/config/runner and unchanged projection helper. Current topology metrics c4b0ca8 and evaluator 95b1a8 are external pinned repo dependencies, not duplicated or modified. Config 503cb38b; adapter bb06a646.
- Compact replay receipt and measurement retain every scalar/field except explicitly marked large original geometry and patch/witness arrays. Omitted arrays have counts and canonical JSON hashes in `artifact-bindings.json`; the full original artifact SHA is retained. Raw inputs, meshes, binaries and huge arrays are deliberately not distributed.
- `measurement-receipt.json`, `independent-witness.json`, and `new-algorithm-counters.json` are exact bytes. Full replay receipt SHA 8c01f8a5; full measurement report d7ed9620; measurement receipt 43c30dcd.

Configs and receipts preserve historical absolute workspace paths and source commit e188704ddb4aa6a1a9cace8bf48efeb3910690f9. They are evidence, not portable out-of-box commands or promises that mutable branches/APKs still match. Native/header/snapshot bindings remain historical even if contributor identities are corrected later without tree changes. Capture's original source/build evidence is not silently repinned to a newer APK. Reproduction requires the listed immutable external dependencies and a separately reviewed fresh output path; never overwrite production engines or old evidence.

`SHA256SUMS` inventories this archive. It contains no raw photographs, snapshot, mesh arrays or executable binaries. Only /tmp artifacts were written; repository main remained untouched during preparation and measurement.
