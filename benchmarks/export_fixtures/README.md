# Agent 090 synthetic interoperability corpus

All geometry, texture pixels and malformed variants here are first-party generated
by `tools/qa/export_interoperability/make_fixtures.py`; no external photographs or
third-party models are included. Inputs are synthetic analytic fixtures, with
arbitrary scale. They do not count as physical scanning or ground-truth evidence.
Repository ownership/licensing applies; this document does not select a new
project license. Invalid fixtures are intentionally retained for rejection tests.

`066-metric-edit.glb` is actual compact output from the synthetic native066 fixture
at indexed source `a7cabb5bc778b91b056362762ca8c24820587ee1`, using indexed061 C10
`73212fcf78f2dcd31c03cabad4227b5da657d3c5`. Its plain PBR material has no photographic
image or UV channel. `edited_checker_066.cpp` exercises a separate photographic
reflection/crop/metric adapter with checked parent provenance and master immutability.
Both remain analytic fixtures; a synthetic meter reference is not physical accuracy.
