# Photometric depth loss and surface gate attribution

Status: `SYNTHETIC_MECHANISM_ONLY`. These records explain *why* sampled pixels
are rejected before depth consistency and *why* the fusion gate rejects
cross-camera candidates, using counters the production engine already emits plus
two additive subreason breakdowns. No threshold, gate, algorithm or cache
identity is changed, and nothing here is evidence of object coverage, accuracy or
watertightness.

The measurement tables below preserve the synthetic results documented at
baseline `a373aff43a03bd736edb16707cbf5edabf5f2156`; they are not a new
licensed-photo or current-candidate run. The
[19 November M20 preparation record](M20_PREPARATION_2026_11_19.md) tracks the
campaign's source-bound validation. The reader contract below describes the
current code: dense loss schemas 1/2, surface loss schemas 3/4 and report receipt
schema 3.

## Why this exists

The historical `c215632` three-photo HOST journey reports
`31,374 sampled → 4,887 photometric →
2,510 reciprocal → 2,469 exported` dense representatives, and only
`10/2,510` supported normals. The downstream consequence is measured: all
`3,181` cross-camera candidate visits reject at `missingNormal`. Two upstream
questions were open:

1. *Why* 84% of sampled pixels never become photometric depth.
2. Whether the missing-normal gate fails on an unsupported **source** normal or
   an unsupported **accumulator** normal.

`reconstruction/src/dense.cpp` already enforced this per-view identity at
serialization time:

```
acceptedSamples + invalidReferencePatch + validLowVariance
+ invalidOrBoundaryBest + ncc + uniqueness + invalidInverseDepth == evaluatedSamples
```

Dense schema 2 introduced `invalidOrBoundaryBestReasons`; surface schema 3
introduced the current `missingNormalReasons`/accepted-visit reporting contract.
Surface schema 4 retains those counters and adds topology pass attribution.
Subreason totals are validated against their parent counter.
[`dense_loss_report.py`](../../tools/qa/dense_loss_report.py) re-checks the
identity and renders the breakdown; it is read-only and never runs the engine.

## Measured result — photometric stage

Profile: synthetic plane photographs, `104 × 76`, three registered views, near
`3.1`, far `7.0`, `56` planes, pixel stride `3`, through `rf_surface_json` and
`tools/native_api.py`.

| view | evaluated | accepted | rejected | invalidReferencePatch | validLowVariance | invalidOrBoundaryBest | ncc | uniqueness | invalidInverseDepth |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 759 | 663 | 96 | 0 | 0 | 79 | 17 | 0 | 0 |
| 1 | 759 | 759 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 2 | 759 | 667 | 92 | 0 | 0 | 79 | 13 | 0 | 0 |
| total | 2,277 | 2,089 | 188 | 0 | 0 | 158 | 30 | 0 | 0 |

Dominant reason: `invalidOrBoundaryBest`, **84.0%** of rejections.

### Boundary subreasons (schema 2)

| view | ambiguousMinimum | nearBoundaryBest | farBoundaryBest |
|---|---|---|---|
| 0 | 72 | 0 | 7 |
| 1 | 0 | 0 | 0 |
| 2 | 69 | 0 | 10 |
| total | **141** | 0 | 17 |

`ambiguousMinimum` is **89.2%** of the dominant class. The depth-interval edges
account for only 17 of 158, and the near edge for none. On this fixture the
losing samples do not sit outside the search interval; the aggregated cost has
no distinct minimum.

## Measured result — missing-normal gate

| view | sourceNormal | accumulatorNormal | bothNormals | missingNormal first-gate |
|---|---|---|---|---|
| 0 | 999 | 521 | 337 | 1,857 |
| 1 | 793 | 635 | 477 | 1,905 |
| 2 | 1,255 | 416 | 466 | 2,137 |
| total | **3,047** | 1,572 | 1,280 | 5,899 |

An unsupported **source** normal accounts for 51.7% of missing-normal
rejections; both sides unsupported 21.7%; accumulator-only 26.6%. The gate is
therefore mostly reporting that the incoming observation itself has no measured
normal — consistent with sparse depth maps, not with an accumulator-side defect.

Because every candidate records exactly one first-failure gate and the per-gate
counts sum to `fusionCandidates`, the full gate funnel is derivable by
subtraction — no engine instrumentation is added:

```
reached(g) = fusionCandidates - sum(failures of gates before g)
```

`tools/qa/dense_loss_report.py` performs that derivation for supported surface
diagnostics, including `mesh.lossDiagnostics` and saved
`meshPreview.lossDiagnostics` (receipt schema 3), using the engine's evaluation
order rather than an alphabetical one. It refuses an artifact whose gate set is
not exactly the emitted set, whose gate outcomes do not sum to
`fusionCandidates`, or whose missing-normal subreasons do not sum to the
`missingNormal` gate. The remainder left after the last gate equals the reported
`acceptedCandidateVisits` count by construction, so it is reported rather than presented as
independent evidence.

The reader also validates per-view input/accepted/rejected populations and their
mesh totals. When an enclosing mesh supplies the required aggregate counts, it
reconciles proposed welds, local/fallback rollback, restored source vertices and
`fusedObservations` surviving topology admission. A standalone surface
diagnostic object has no complete mesh retention evidence; its retention remains
unknown. Schema 4 pass attribution validates the bounded local/fallback policy
and distinguishes an uninspected pass's null fields from measured zeros.
Its link reasons overlap and cannot be summed as distinct invalid groups. See
the [surface diagnostics contract](DENSE_SURFACE_LOSS_DIAGNOSTICS.md#schema-4-topology-pass-attribution).

### Reached-conditional funnel (56x3, same fixture)

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
| anchorSeesSample | 0 | 612 | 0.0% | 0.0% |
| sampleSeesAnchor | 0 | 612 | 0.0% | 0.0% |
| memberAnchorMetric | 0 | 612 | 0.0% | 0.0% |
| memberSeesProposed | 0 | 612 | 0.0% | 0.0% |

Two readings change when the denominator is the candidates that actually reach a
gate. `missingNormal` accounts for 40.1% of the 14,704 candidates that survive
the same-camera check, not 18.8% of all visits. `outsideRadius` then rejects
73.0% of the 7,223 candidates that passed the normal, mismatch and proximity
checks — the largest conditional loss in the chain — and `pairMetric` rejects
59.5% of the 1,510 that reach it. The association chain is therefore not merely
starved by absent normals; among otherwise plausible candidates it is dominated
by radius and pair-metric admissibility. Both are geometric acceptance
conditions, and changing either is a production gate decision that this record
does not authorize.

## Decision rule (pre-declared, unchanged by results)

| Share of rejections | Band | Meaning |
|---|---|---|
| ≥ 40% | `DECISIVE` | One mechanism explains most of the loss; investigate it first |
| 25–40% | `LEADING_BUT_MIXED` | Leading cause exists but a second mechanism is material |
| < 25% | `NO_SINGLE_CAUSE` | Loss is distributed; a single-gate change is unjustified |

Indicated investigation direction per dominant reason:

| Reason | Direction |
|---|---|
| `validLowVariance` | Capture/preprocessing contrast gap, not a matcher defect |
| `invalidOrBoundaryBest` | Refined by subreason: ambiguous minimum → spacing/patch/parallax; edge → search interval |
| `ncc` | Exposure, rectification or fronto-parallel warp |
| `uniqueness` | Repetitive texture or insufficient parallax |
| `invalidReferencePatch` | Mask/boundary validity bookkeeping |
| `invalidInverseDepth` | Local refinement guard |

A band never authorizes a threshold change. Any intervention must be re-measured
on the same fixture and must not regress intersections, nonmanifold edges or
boundary edges.

## Read an existing artifact

The report command consumes retained JSON and does not require a native build.
It accepts native `dense`/`mesh` objects, saved Android
`densePreview`/`meshPreview` envelopes, direct result objects and supported
standalone diagnostics. Conflicting aliases and ambiguous direct/enclosed
objects are rejected. Choose an existing parent directory and a new receipt
filename for each run:

```sh
python -m tools.qa.dense_loss_report \
  --input /absolute/path/to/published-preview.json \
  --label "fixture/settings label" \
  --json-out /absolute/path/to/existing-evidence-directory/loss-receipt-001.json
```

Omit `--json-out` to print only Markdown. When a JSON receipt is requested, the
CLI reads the input bytes once, parses those same bytes and records their exact
SHA-256 and byte count under `provenance.input`. It does not copy the input path
into that identity. The hash identifies the supplied artifact; it does not
establish its original producer, build source or freshness. Pure Python
`receipt(...)` calls have `provenance: null` unless the calling CLI supplies
actual input-byte evidence.

| Exit | Meaning |
|---|---|
| `0` | At least one valid dense or surface report was produced, and any requested receipt was published |
| `1` | Malformed input, contradictory accounting, read/decode error or receipt-publication failure |
| `2` | No reportable originating dense or surface diagnostics are available |

Dense schemas 1 and 2 remain readable; the boundary subreason split appears only
when present. Surface schema 3 remains readable with aggregate retention;
schema 4 adds per-pass attribution. Absence is never silently converted to zero.
The dense reader checks coverage labels, evaluated/unknown sample partitions and
bounded histogram bins. It validates other views before treating an unavailable
view as missing evidence, so a reused map cannot hide a malformed fresh view.
An invalid surface section likewise fails the command even if its dense sibling
is valid.

The output filename must be unused, including no existing symlink, hardlink
alias or directory. Publication writes and fsyncs a sibling temporary file,
then uses an exclusive hard link to expose the complete JSON without replacing
an existing destination. A destination created by another writer after preflight
is preserved. Filesystems without hardlink support fail explicitly. The helper
does not fsync the parent directory, so atomic visibility is not a guarantee of
persistence through sudden power loss. Successful report stdout follows any
requested receipt publication.

Saved counts describe the **originating geometry evaluation**. A reused preview
may retain them with `geometryReused=true`; reopening or importing that artifact
does not establish fresh reconstruction. A checkpoint without originating
diagnostics reports unavailable/null fields. Inspect reuse, resumed-depth and
per-view coverage records before claiming a fresh full evaluation.

For an actual baseline/candidate comparison, use the separate
[synthetic parity procedure](DENSE_SURFACE_LOSS_DIAGNOSTICS.md#resource-and-verification-contract)
with independently built libraries and a new output directory outside the
repository. Reading an existing report does not execute that comparison.

## Open evidence

- A new licensed three-photo attribution run for this campaign: **NOT_RUN**.
  The historical HOST measurements above remain tied to their original source.
- Android/controller pixel execution for this campaign: **NOT_RUN**. Parsing a
  saved controller-shaped envelope is host reader coverage, not device execution.
- Broader effects of depth-range, spacing or patch-size changes remain
  unqualified. The separate [profile sweep](DENSE_PROFILE_SWEEP.md) is bounded
  synthetic mechanism evidence and does not authorize a production change.
- Physical object coverage, accuracy, watertightness: unchanged and unqualified.
