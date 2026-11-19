# Depth profile sweep and fusion gate funnel

Status: `SYNTHETIC_MECHANISM_ONLY`. Every number below comes from the actual
bounded native surface stage on the labelled synthetic plane fixture (`104×76`,
three registered views, near `3.1`, far `7.0`, seeded texture `504`). No
production default, admission limit, cache identity or gate was changed.

The sweep and gate-funnel measurements were already recorded at baseline
`a373aff43a03bd736edb16707cbf5edabf5f2156`. The 19 November preparation keeps
those measured values, corrects their qualification decisions and adds schema-4
rollback attribution. See the [campaign record](M20_PREPARATION_2026_11_19.md)
for the integrated source and validation evidence.

## Question

The photometric loss is dominated by `invalidOrBoundaryBest` and, inside it, by
`ambiguousMinimum`. `matching::distinctMinimum` marks a proposal ambiguous when
**no competing minimum exists outside a ±3-plane window**. Two explanations were
live:

1. Quantisation — more planes would resolve a real but under-sampled peak.
2. Visibility/parallax — outside the narrow mutually-visible depth band every
   hypothesis is an invalid cost, so no alternative can ever be observed.

## Sweep result

Use the [reproduction commands](#reproduction) with a new receipt destination.
All six measured intersection checks completed (`topologyIncomplete=false`).

| profile | evaluated | rejected | ambiguous | boundaryEdge | ncc | normals | fusions | tris | boundary | intersections | nonmanifold | decision |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 40x3 | 2,277 | 191 | 171 | 11 | 9 | 1,335 | 0 | 3,836 | 336 | 4,725 | 0 | REJECT |
| **56x3** | 2,277 | 188 | 141 | 17 | 30 | 1,410 | 0 | 3,852 | 324 | 4,464 | 0 | **BASELINE** |
| 72x3 | 2,277 | 191 | 140 | 17 | 31 | 1,295 | 0 | 3,836 | 340 | 4,216 | 0 | REJECT |
| 96x3 | 2,277 | 221 | 138 | 16 | 33 | 1,184 | 0 | 3,704 | 448 | 3,933 | 0 | REJECT |
| 56x2 | 5,145 | 448 | 320 | 20 | 106 | 2,549 | 0 | 8,840 | 576 | 8,273 | 0 | REJECT |
| 72x2 | 5,145 | 457 | 318 | 22 | 104 | 2,410 | 0 | 8,777 | 629 | 7,918 | 0 | REJECT |

Findings:

- **More planes provide little ambiguity improvement on this fixture.** Raising
  56 → 96 planes changes ambiguity from 141 to 138 (2.1%) while reducing
  supported normals from 1,410 to 1,184. This weakens the simple hypothesis that
  increasing plane count resolves the observed loss here. It does not rule out
  quantisation effects on other inputs or establish visibility as the sole cause.
- **Near/far edge rejections stay at 11–22** across these profiles. Their small
  count does not establish that the declared interval covers every true depth;
  this sweep does not independently vary or qualify the interval.
- **Stride 2 increases sampling** (2,277 → 5,145 evaluated) and normals
  (1,410 → 2,549), but also increases intersections (4,464 → 8,273) and boundary
  edges (324 → 576), with no retained fusions. The result is `REJECT`.
- **All five alternatives are rejected.** `72x3` and `96x3` increase boundary
  edges to 340 and 448. Their lower intersection counts do not compensate for
  that regression. No candidate produces a retained fusion or qualifies for
  promotion.

## Qualification rule

`PROMOTE` requires both more supported normals and more retained fusions, with
no increase in boundary edges, intersections or nonmanifold edges. The baseline
and candidate intersection checks must both be complete. This is a synthetic
profile decision; even `PROMOTE` would not authorize a production-default change.

Known boundary or nonmanifold regressions produce `REJECT`, including when an
intersection check is incomplete. An incomplete intersection count is a lower
bound: it proves an intersection regression only when it already exceeds a
**complete** baseline count. A larger count than an incomplete baseline alone
does not prove regression. Without another known regression, incomplete evidence
produces `INCONCLUSIVE`. Complete, nonregressing evidence without both required
gains also produces `INCONCLUSIVE`.

The receipt records the declared rule, baseline and individual decision reasons.
It recomputes those decisions from validated metrics rather than trusting
supplied decision labels. A conflicting second measurement using the baseline's
profile name is malformed evidence.

## Fusion gate funnel (56x3)

Every candidate visit records one outcome: its first rejection gate or an
accepted candidate visit. The counts sum to `fusionCandidates = 31,355`.
`reached` is derived by subtraction in the
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
reach it. Both are geometric acceptance conditions. On the separate historical
licensed three-photo HOST fixture at `c215632`, normals were 10/2,510 and
`missingNormal` rejected all 3,181 cross-camera candidate visits. These fixtures
lose evidence at different stages; neither measurement generalises to physical
object quality. That real-photo fixture was not rerun for this sweep.

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
- **The earlier counter name and this document's reading were misleading.**
  Surface loss diagnostics schema 3 renamed the key to
  `"acceptedCandidateVisits"`; schema 4 retains that name and adds per-pass
  attribution. The value is never a retained-fusion count.

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

### Schema-4 pass attribution

The candidate's actual native JSON gives the following bounded pass history for
the same `56x3` fixture:

| Pass | Link inspection | Weld groups before this pass | Invalid groups | Groups rolled back | Restored source vertices |
|---|---|---:|---:|---:|---:|
| 1 | Inspected | 583 | 569 | 569 | 1,167 |
| 2 | Inspected | 14 | 14 | 14 | 28 |
| 3 | Uninspected; no weld groups remain | null | null | null | null |

Thus `locallyRolledBackWeldGroups=569` and
`fallbackRolledBackWeldGroups=14`; their sum is all 583 proposed groups.
The aggregate `restoredSourceVertices` is 1,195 and final
`retainedWeldGroups=0`. Within an inspected pass, the field named
`retainedWeldGroups` counts groups present **before that pass's rollback**; it is
not the final retained aggregate.

First-pass invalid-link witnesses are:

| Witness | Groups with that witness |
|---|---:|
| `branchingLink` | 523 |
| `directionConflict` | 523 |
| `disconnectedLink` | 48 |
| `invalidEndpointCount` | 0 |

A group may have more than one witness. These counts therefore must not be added
to obtain the 569 invalid groups. The second pass reports 12 branching, 12
direction-conflict and two disconnected-link witnesses, with zero invalid
endpoint-count witnesses. Its fallback restores all 14 remaining groups.
The final uninspected pass carries null counters; it is not a measured successful
link inspection. This instrumentation attributes the existing rollback and does
not alter link admission, candidate gates or geometry.

## Reproduction

```sh
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --target realityforge --parallel 4
sweep_evidence_dir=$(mktemp -d "${TMPDIR:-/tmp}/rf-profile-sweep.XXXXXX")
RF_NATIVE_LIBRARY="$PWD/build/host/librealityforge.so" PYTHONPATH=. \
  python -m tools.qa.dense_profile_sweep \
  --json-out "$sweep_evidence_dir/sweep.json"
```

Use the [pinned host QA environment](../BUILDING.md#independent-host-qa-dependencies).
Each invocation needs a new output path whose parent already exists. Existing
files, directories, symlinks and input aliases are refused; the tool does not
replace a prior receipt. Successful stdout follows receipt publication. Exit 0
means the sweep completed, not that a profile was promoted. Exit 1 indicates
invalid input or publication failure; exit 2 indicates unavailable native code
or required originating diagnostics.

Machine-readable sweep receipts use **schema 2**. A native invocation carries
SHA-256 and byte counts for the library and helper sources, identities of the
exact normalized RGBA/camera buffers, and available Git context. The evidence is
bound to its measured rows and decisions. Binary identity does not establish the
source used to build that binary: `nativeLibraryBuildProvenance` remains null.
Pure receipt construction without captured execution evidence carries
`provenance:null`. Receipt schema 2 is separate from the native surface diagnostic
schema 4 and the loss-report receipt schema 3.

## Limits

- Synthetic fixture only; a new licensed three-photo run for this sweep remains
  **NOT_RUN**. Historical HOST results remain separately attributed.
- Host ctypes path; no Android/controller pixels, no device, no physical object.
- Intersection counts are large. These six checks completed within the bounded
  analyzer; future incomplete checks must retain their lower-bound status. Counts
  are used only under the fixed comparison rule, never as a quality score.
- No production default, limit, gate order or cache identity was changed.
