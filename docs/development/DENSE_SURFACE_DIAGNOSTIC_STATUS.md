# Dense/surface diagnostic checkpoint — 25 October 2026

## Fresh production HOST evidence: `c215632`

The [fixed licensed three-photo HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40227532716)
passes with three registered cameras and verified publication/export/reopen.
STANDARD/Compatibility remains **384 × 256, stride 3, 56 planes**. All 72 tiles
were fresh; no depth maps/tiles resumed, no geometry reuse or cap reached.

The measured chain is **31,374 sampled → 4,887 photometric → 2,510 reciprocal →
2,469 exported dense representatives**. Surface accounting is **2,510 input →
2,506 accepted → 1,618 attached + 888 unattached**, producing 1,296 triangles.
There are no cross-view fusions, 1,580 boundary edges and 558 reported
intersections, despite zero nonmanifold edges. This remains fragmented and
non-watertight, not complete-object, metric or device-quality evidence.

All **3,181 cross-camera candidate visits reject at missingNormal**. Only
**10/2,510 normals** are supported. Of 2,500 unsupported normals, 2,308 have
missing-depth-neighborhood witnesses and 39 have depth-jump witnesses; 153 lack
those witnesses. Missing depth anywhere in the current 3×3 window rejects before
PCA; all nine samples are used, not merely unchecked corners. The missing-normal
gate does not distinguish an unsupported source normal from accumulator normal.
Later normal/radius/visibility/pair/member gates were **not exercised**, not
proved correct. These counts do not justify relaxing association thresholds.

The mesh counts and triangle arrays match `f6cdd12`; dense/mesh/textured GLB and
texture PNG bytes match exactly. Not all outputs are byte-identical: small JSON
floating differences and PLY/sparse byte differences remain; photometric accepts
are 4,887 rather than 4,888. This is loss attribution, not a quality improvement.

## Rejected partial-normal experiment — not promoted

A local sufficient-observed-subset prototype kept the measured center, connected
surrounding support, present-depth/confidence vetoes and existing PCA conditions.
Independent analytic controls covered **156,240 cases / all 256 neighbor masks**,
with **586 exact complete-nine comparisons**. Both narrower policies still newly
accepted step scenes rejected by the full-nine baseline:

| Partial policy | Newly accepted steps | Of those, normal error >10° |
| --- | ---: | ---: |
| At least seven samples, all four axial neighbors | 156 | 92 |
| Exactly one missing corner | 72 | 40 |

These are corpus counts, not probabilities or proposed quality thresholds.
A local fixed-capture native replay used the same licensed originals and captured
poses/bounds but **different OpenCV-decoded pixels**, not Android/controller
pixel equivalence. Its baseline had 2,527 verified samples, 1,625 vertices and
1,353 triangles. Normal probes increased support from 15 to 37 or 34 respectively;
these are **not fusion or quality gains**. Candidate fusion was not run after
safety rejection. No partial-normal policy, cache identity, production default
or geometry gate was promoted. Local probes do not replace a fresh production
HOST comparison; leak checking was unqualified under that runner's ptrace limit.

## Existing six-iteration depth refinement probe — not promoted

A separate local probe enabled only the existing six local-depth-refinement
iterations on the unchanged `c215632` native implementation. It used the same
local fixed-capture inputs and **different decoded pixels from the production
HOST** as above: `PUBLIC_FIXED_CAPTURE_NATIVE_REPLAY_NOT_PRODUCTION_CONTROLLER_NOT_QUALITY`.
Both local modes retained the same photometric mask/confidence hashes and 4,878
accepted depths. Local common-photometric normal support increased from 19 to 100;
reciprocal normal support increased from 15 to 82. That was not sufficient for
better topology:

| Local replay metric | Baseline | Six local iterations |
| --- | ---: | ---: |
| Cross-view fusions | 0 | 7 |
| Triangles | 1,353 | 1,401 |
| Boundary edges | 1,557 | 1,587 |
| Reported intersections | 600 | 829 |
| Nonmanifold edges | 0 | 2 |

These comparisons are local replay against local replay, not against the
production `c215632` mesh or Android pixels. More supported normals/fusions did
not establish a quality gain; topology worsened. No default, production source,
cache identity or acceptance threshold was changed or promoted. Read-only
provenance investigation continues; no validated production repair follows yet.

## Coarse-only probe — not promoted

A further bounded probe used the same public frozen local replay, with **no local
or normal refinement**. It accepted 548 coarse proposals while preserving the
4,878-sample photometric mask/confidence evidence. Supported reciprocal normals
increased from 15 to 31, but fusion stayed zero. Against the local baseline,
triangles increased from 1,353 to 1,493, reported intersections from 600 to 751,
and boundary edges from 1,557 to 1,641; nonmanifold edges remained zero.
This different-pixel local replay did not establish a quality improvement and
was not promoted. A later coarse-plus-three-normal-iterations probe is explicitly
**NOT_RUN**; no result is inferred from the coarse-only experiment.
