# Oracle-camera dense observer: pre-execution review

Reviewed frozen manifest `148bbd5915128538bd2abd2e2ce8a5c49aa097c133c249aaebff7e155a77fb25` against native source 173dc71. Ready for the prescribed first bounded baseline/observer attempt; this is not a measurement or runtime qualification.

- Logging anchors retain original branch conditions and helper results. No additional NCC, depth consistency, normal query, sample admission or face decision is introduced inside the observed library. Roundtrip hypot and relative-error float conversion are captured once at the original decision location.
- Directed records use exactly 24 explicit little-endian uint64 words (192 bytes), not C++ padding. Validity flags distinguish unavailable values. Actual generated source IDs and plans bind records; strict reverse identity requires two actual confirmed directed records and verified endpoints, not supportingViews alone.
- Baseline/observer comparison serializes complete DenseResult and SurfaceMesh fields, ordered samples, maps, source plans and diagnostics. Pre-face logging preserves unknown normals and all accumulators. Input/surface mutation checks and original-helper replay are present.
- Fixed eight-view oracle poses, near/far/planes/stride are explicitly supplied, not estimated SfM. Solver binary input contains only images and calibrated camera rows. Scene geometry/masks stay outside native input; separate whole-visible evaluator runs after exact output parity and seal. No crop, fitted gauge or front is admitted.
- Bounded cleanup helper is byte-identical to the independently tested direct-depth observer helper. Fresh outputs, source/tool/library binding, native AS/CPU/wall and shared sidecar limits are enforced. Runtime prerequisites must fail rather than silently modify unexpected inherited overrides.
- Empty verified samples return before pre-face hooks; no-face exports legitimately refuse. Predetermined baseline stage-summary/outcome retains dense evidence and labels observer/audits/evaluation NOT_RUN. No fabricated empty GLB or retuned fixture.

Corrections before first run: exact parsing through trailing dense counters/EOF and per-view generated accounting; strict record flags/unused-word/nearest-return checks; cross-thread helper refusal; explicit empty outcome; decoder interpreter-variable shadowing fixed. No native measurement was used to choose these changes.

All package hashes and Python syntax verified. Native compilation, baseline/observer parity, witness numerical audit and evaluation remain NOT_RUN at this checkpoint. Stop at first native/parity/resource defect and preserve evidence; no tuned retry is approved by this review. No root edits or remote writes.
