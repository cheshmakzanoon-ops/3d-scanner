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
to `fusionCandidates = 31,355`. `reached` is derived by subtraction in the
engine's evaluation order by `tools/qa/dense_loss_report.py`, which rejects a
gate set that does not match the emitted set or outcomes that do not sum to
`fusionCandidates`:

| gate | failed | reached | failure of reached | share of all visits |
|---|---|---|---|---|
| sameCamera | 16,651 | 31,355 | 53.1% | 53.1% |
| missingNormal | 5,899 | 14,704 | 40.1% | 18.8% |
| normalMismatch | 192 | 8,805 | 2.2% | 0.6% |
| notCloser | 1,390 | 8,613 | 16.1% | 4.4% |
| outsideRadius | 5,274 | 7,223 | **73.0%** | 16.8% |
| currentVisibility | 0 | 1,949 | 0.0% | 0.0% |
| proposedAnchorMetric | 439 | 1,949 | 22.5% | 1.4% |
| proposedVisibility | 0 | 1,510 | 0.0% | 0.0% |
| pairMetric | 898 | 1,510 | **59.5%** | 2.9% |
| anchorSeesSample / sampleSeesAnchor / memberAnchorMetric / memberSeesProposed | 0 | 612 | 0.0% | 0.0% |
| **acceptedCandidateVisits** | — | 612 | — | 2.0% |

This is the "later gates were not exercised" gap, exercised, and the conditional
denominator changes which gate looks responsible. `missingNormal` takes 40.1% of
the candidates that reach it, but the largest conditional losses sit later:
`outsideRadius` rejects 73.0% of the 7,223 candidates that pass the normal,
mismatch and proximity checks, and `pairMetric` rejects 59.5% of the 1,510 that
reach it. Both are geometric acceptance conditions. On the production fixture,
by contrast, normals are 10/2,510 and `missingNormal` takes essentially
everything, so the two fixtures fail for different reasons and neither can be
generalised.

## Resolved — topology rollback, not a merge or reporting defect

The 612 entries counted under the old `"accepted"` key are **admissible
candidate visits**, not retained fusions. The merge path does run: **583 weld
groups** were formed (`proposedWeldGroups = 583`). Topology admission then ran
three passes and **rolled back all 583** (`rolledBackWeldGroups = 583`,
`restoredSourceVertices = 1,195`), because the welded vertex stars were not
admissible. The rollback rebuild in `reconstruction/src/surface.cpp` restores
one vertex per source sample, sets `fusedObservations = 0` and recomputes it as
`sum(sourceCount - 1)` over retained groups, which is zero when every group is
rolled back. Hence `vertexCount = acceptedSamples = 2,087`,
`canonicalVertexCount = 2,087` and an empty `topologyAliasSources`.

So neither earlier hypothesis held:

- **No missing-merge defect.** 583 groups were merged; the rollback removed them
  deliberately.
- **No lost-counter defect.** `fusedObservations` is recomputed on rebuild; it
  was never dropped by `normalizeSurfaceAliases`.
- **The defect was the counter name and this document's reading of it.** The key
  is now emitted as `"acceptedCandidateVisits"` (surface loss diagnostics schema
  3) so it cannot be read as retained fusion.

Exact divergence: `surface.cpp` increments the accepted gate where a candidate
is *admissible*, while the retained count is only produced by the merge and then
recomputed by `restoreSources` during topology rollback. 612 visits exceed 583
groups because a candidate can be admissible and later superseded by a closer
one (`minDist`), so visits do not map one-to-one onto weld groups.

**Does cross-view fusion demonstrably occur on the dense fixture?** It is
demonstrably attempted and geometrically accepted — 583 weld groups — but it is
**not retained**: topology admission rejects every group on this fixture. The
measured reason is welded-topology inadmissibility, not absent observations,
absent normals or a lost counter.

A controlled overlapping-plane fixture does retain fusion: the native
`surface_fusion_test` asserts `fusedObservations > 20`, that retained vertices
are fewer than accepted samples, that not every proposed group was rolled back,
and that the reported JSON carries the same value. That test is the executed
proof that the retention path works when the topology is admissible; the dense
fixture's zero is a rollback result.

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
