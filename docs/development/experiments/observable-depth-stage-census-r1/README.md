# Observable depth-stage census

Evaluation-only source checkpoint. Whole census independently reproduced: EVALUATION_ONLY_COMPLETE. Original solver inputs and outputs remain unchanged; this is not image-derived registration or an algorithm improvement.

```sh
python -B run.py SOURCE_CELL_REPLAY FIXTURE_PACKAGE MATCHED_NATIVE_SOURCE FRESH_OUTPUT QA_PYTHON controls
python -B run.py SOURCE_CELL_REPLAY FIXTURE_PACKAGE MATCHED_NATIVE_SOURCE FRESH_OUTPUT QA_PYTHON census
```

Prerequisites: reviewed source-cell-binding replay, committed new-cuboid-eight-view-160x120 fixture and its pinned 173dc71 baseline source. Existing QA NumPy/trimesh only, no new dependencies. Reused cuboid evaluator, native witness auditor and bounded helper retain their exact predecessor bytes. Fresh external outputs, fail-closed identity/runtime checks. Do not interpret an offset grid ray on another face as missing depth at the original reference sample. See CONTRACT.md for limits and missing-evidence semantics.

Premeasurement rounding qualification is preserved under evidence/rounding-preflight: the initial generic warning was not a demonstrated fixture defect. Parent selected broader-domain modf hardening and compiled native oracle controls; the numerical hardening preceded the first whole census.

## Measured localization

Executed manifest `bb8c4539c580cfef3191384ceff7fa916a90bd23abe74544245d02f9a26aa400`. Author and independent runs reproduce every data artifact, all 22,157 visible sample/view rows and all 3,734 observable reference samples. All 13 pinned inputs remain unchanged. Large CSV/reference-row outputs are excluded from publication but are regenerated and hash-bound by the compact receipt.

At every visible selected grid ray, 1,119 reference samples have zero photometric depth. Highest retained stage for the remaining samples is 340 positive-but-unverified, 547 accepted-but-not-source-triangle-incident and 1,728 source-incident. These are retention categories, not accuracy or coverage claims. At 1% reference diagonal, 1,333 samples have some same-face accurate positive map depth versus 1,277 with a verified map sample.

For the 1,360 reference samples outside the conservative all-point reachability bound, 6,571 of 7,195 visible rows have zero photometric depth. Of all 11,158 zero-depth visible rows, 10,618 select the same physical truth face, 446 background and 94 another face. The same-face zeros (2,614 unique grid pixels) all have truth camera-z strictly inside the unchanged 3..7 search interval: min 4.0617697146761795, max 6.188615986787876; no outside-range or exact-boundary cases.

This localizes substantial missing support before native consistency, not to the declared search range or fusion alone. It does NOT identify the photometric rejection reason: reference variance, invalid/boundary minimum, NCC quality, uniqueness and inverse-depth exits remain unobserved here. No threshold was changed and no geometry improvement is claimed. Offset grid-ray evaluation is explicitly separated from original sample depth; independent slab-ray checks match the frozen raster within 5.33e-15.
