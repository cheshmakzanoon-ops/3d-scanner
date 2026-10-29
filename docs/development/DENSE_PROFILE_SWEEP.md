# Depth profile sweep and fusion gate funnel

Status: `SYNTHETIC_MECHANISM_ONLY`. Every number below comes from the actual
bounded native surface stage on the labelled synthetic plane fixture (`104×76`,
three registered views, near `3.1`, far `7.0`, seeded texture `504`). No
production default, admission limit, cache identity or gate was changed.

## Question

The photometric loss is dominated by `invalidOrBoundaryBest` and, inside it, by
`ambiguousMinimum`. `matching::distinctMinimum` marks a proposal ambiguous when
**no competing minimum exists outside a ±3-plane window**. Two explanations were
live:

1. Quantisation — more planes would resolve a real but under-sampled peak.
2. Visibility/parallax — outside the narrow mutually-visible depth band every
   hypothesis is an invalid cost, so no alternative can ever be observed.

## Sweep result

Command: `python -m tools.qa.dense_profile_sweep --json-out sweep.json`

| profile | evaluated | rejected | ambiguous | boundaryEdge | ncc | normals | fusions | tris | boundary | intersections | nonmanifold | decision |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 40x3 | 2,277 | 191 | 171 | 11 | 9 | 1,335 | 0 | 3,836 | 336 | 4,725 | 0 | REJECT |
| **56x3** | 2,277 | 188 | 141 | 17 | 30 | 1,410 | 0 | 3,852 | 324 | 4,464 | 0 | **BASELINE** |
| 72x3 | 2,277 | 191 | 140 | 17 | 31 | 1,295 | 0 | 3,836 | 340 | 4,216 | 0 | INCONCLUSIVE |
| 96x3 | 2,277 | 221 | 138 | 16 | 33 | 1,184 | 0 | 3,704 | 448 | 3,933 | 0 | INCONCLUSIVE |
| 56x2 | 5,145 | 448 | 320 | 20 | 106 | 2,549 | 0 | 8,840 | 576 | 8,273 | 0 | REJECT |
| 72x2 | 5,145 | 457 | 318 | 22 | 104 | 2,410 | 0 | 8,777 | 629 | 7,918 | 0 | REJECT |

Findings:

- **Quantisation is ruled out.** Raising 56 → 96 planes changes ambiguity from
  141 to 138 (2.1%) while reducing supported normals from 1,410 to 1,184.
  Ambiguity is not a plane-spacing artefact.
- **Boundary rejections stay at 11–22** across every profile, so the declared
  `near`/`far` interval is not the cause either.
- **Stride 2 doubles sampling** (2,277 → 5,145 evaluated) and normals
  (1,410 → 2,549) but nearly doubles intersections (4,464 → 8,273) with no
  fusions. Under the pre-declared rule that is a `REJECT`.
- **No profile is promoted.** The rule requires more normals *and* more fusions
  with no topology increase; no candidate produced any fusion at all.

## Fusion gate funnel (56x3)

Every candidate visit records exactly one first-failure gate, and the counts sum
to `fusionCandidates = 31,355`:

| gate | candidates | share |
|---|---|---|
| sameCamera | 16,651 | 53.1% |
| missingNormal | 5,899 | 18.8% |
| outsideRadius | 5,274 | 16.8% |
| notCloser | 1,390 | 4.4% |
| pairMetric | 898 | 2.9% |
| proposedAnchorMetric | 439 | 1.4% |
| normalMismatch | 192 | 0.6% |
| **accepted** | **612** | 2.0% |
| currentVisibility / proposedVisibility / anchorSeesSample / sampleSeesAnchor / memberAnchorMetric / memberSeesProposed | 0 | 0% |

This is the "later gates were not exercised" gap, exercised. With 1,410
supported normals on this fixture, `missingNormal` is only 18.8% of visits;
`outsideRadius` and `notCloser` together account for 21.2%, and the association
chain does accept 612 visits. On the production fixture, by contrast, normals
are 10/2,510 and missingNormal takes essentially everything — so the two
fixtures fail for different reasons and neither can be generalised.

## Open contradiction — reported, not resolved

`fusionCandidateFirstGate.accepted = 612`, but the same mesh JSON reports
`fusedObservations = 0`, `vertexCount = 2087` and `acceptedSamples = 2087`
(vertex count equals accepted samples exactly). An accepted candidate should
merge its source into an existing accumulator, which must reduce the accumulator
count and increment `fusedObservations`.

Two possibilities remain and are **not distinguished** here:

1. The accepted counter records admissibility while the merge path is not taken
   (a real fusion defect), or
2. `normalizeSurfaceAliases` rebuilds the mesh without carrying
   `fusedObservations`, so the published counter under-reports (a reporting
   defect), while vertex expansion from alias separation masks the merge.

Both are receipt-visible inconsistencies and must be settled before any
association or topology repair, because they change what "zero cross-view
fusion" actually means. This record does not choose between them and does not
alter either path.

## Reproduction

```sh
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --target realityforge --parallel 4
PYTHONPATH=. python -m tools.qa.dense_profile_sweep --json-out sweep.json
```

## Limits

- Synthetic fixture only; the licensed three-photo artifact remains **NOT_RUN**.
- Host ctypes path; no Android/controller pixels, no device, no physical object.
- Intersection counts on this fixture are large and budget-bound; they are used
  only for relative comparison under the fixed rule, never as a quality score.
- No production default, limit, gate order or cache identity was changed.
