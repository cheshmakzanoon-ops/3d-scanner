# Immutable camera-partition successor

This separate revision changes only the exhaustive validation reference enumeration: immutable camera buckets replace scans of triangles belonging to unrelated cameras. Geometry, support certificate, native diagnostics, ordering, radius and all resource/quality limits are unchanged. The first whole attempt was independently reproduced as NO_GO_INCOMPLETE_ARM: SUPPORT_WORK_BUDGET.

The original arm exhausted its 5,000,000 shared visit cap at visit 5,000,001. Its 192 brute validation queries charged 3,019,392 visits. Construction of all eight buckets charges 15,734 visits; all relevant triangle checks plus 192 query charges cost 377,616 visits. Expected reduction is 2,626,042 visits. Other work is not profiled, and this is not a promise of completion or quality.

```sh
python -B run.py controls FRESH_OUTPUT QA_PYTHON
python -B run.py proof SOURCE_CELL_REPLAY FRESH_OUTPUT QA_PYTHON
# Only after the frozen source/control/equivalence pre-run gate:
python -B run.py whole SOURCE_CELL_REPLAY FIXTURE_PACKAGE FRESH_OUTPUT QA_PYTHON
```

Source-cell replay and fixture prerequisites are unchanged from the original package. No dependencies are added. Every run verifies the package manifest before and after; inherited runtime overrides fail closed. Fresh external outputs only.

Proof mode runs the original brute scan and the new partition enumeration on all 192 fixed queries, comparing exact ordered compact IDs and retaining their original attempt IDs. Its separate bounded QA process has its own unchanged 5M limit; it never emits geometry. Whole mode repeats this QA gate, then invokes geometry with one unchanged 5M shared budget charged for partition construction, index and all support work. This is not a budget reset within the geometry algorithm. All eight partitions, including empty buckets, remain explicit. Missing partitions, duplicate original IDs and invalid domains refuse.

The original executed bridge and manifest are retained under evidence/predecessor. R3 certificate modules, front, gates, native diagnostics and config are byte-identical to the original executed arm. No observations or views are removed, and unsupported normals remain unknown. Candidate files before a final outer receipt are scratch only; any budget or later failure invalidates publication. Analytic controls do not establish photographic quality.

## Preserved measured result

Executed manifest: `ca4db9c4e662a73d2c9b02e7c82973e953f36805e62d70a6492190dec5879896`. Author and independent attempts both reached 5,000,001 shared visits / 48,033 ball checks and refused during closed-boundary subdivision. The proven 2,626,042-visit reduction did not make full support feasible. There was no candidate export, native whole-mesh diagnostics, evaluation or success receipt; partial admitted-face counts were not retained. All 28 predecessor inputs remained unchanged. This is a verified optimization and preserved resource refusal, not a geometry-quality comparison. No further residual phase profiling is claimed.
