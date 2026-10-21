# Independent export interoperability, protocol 1

This is desktop-only QA. The Android parser is never imported. Qualified tools:
Khronos glTF Validator `2.0.0-dev.3.10` (Apache-2.0), Trimesh `4.5.3` (MIT),
Blender `4.3.2` (GPL-3.0-or-later, executed as an external program).
These tools are not redistributed in the APK. Blender 4.3 is checked by the adapter;
capture its full patch version with the command below in each release receipt.

From the checkout, with the existing Python 3.12/numpy/scipy/Pillow/pytest workflow:

```bash
python -m pip install --no-deps --target /tmp/rf-interop-python trimesh==4.5.3
npm install --cache /tmp/rf-npm-cache --prefix /tmp/rf-interop-npm --ignore-scripts --no-audit --no-fund gltf-validator@2.0.0-dev.3.10
export PYTHONPATH=/tmp/rf-interop-python
export RF_GLTF_VALIDATOR=/tmp/rf-interop-npm/node_modules/gltf-validator
blender --version
pytest -q tests/test_export_interoperability.py
python -m tools.qa.export_interoperability.validate benchmarks/output/sparse.glb --output /tmp/sparse-glb.json
python -m tools.qa.export_interoperability.validate benchmarks/output/sparse.ply --output /tmp/sparse-ply.json
python -m tools.qa.export_interoperability.validate benchmarks/export_fixtures/asymmetric.glb --expect benchmarks/export_fixtures/textured.expected.json --roundtrip --output /tmp/triangle-glb.json
```

Use the same last command for OBJ with `textured.expected.json`, and PLY/STL
with `geometry.expected.json`. Round-trips invoke Blender with factory settings,
one thread, a timeout, a private temporary output directory and Python failure
exit propagation. GLB is validated before and after export. OBJ texture sidecars
must remain in the output package; Blender export uses copy mode. Every point
location is compared bidirectionally with tolerance `1e-5`, triangle counts and
representation must match, and texture corner samples verify orientation/color.
Vertex welding is allowed. Original files are never overwritten.

The asymmetric synthetic triangle has bounds `(0,0,0)` to `(2,1,3)` and three
distinct texture corners. Its normal agrees with its winding. Companion JSON
defines expected axes/scale and samples independently of the reader. Other corpus
members deliberately contain bad indices, reversed normals, vertical UV flips,
unproven metric claims, missing sidecars and traversal. Regenerate deterministically:
`python -m tools.qa.export_interoperability.make_fixtures benchmarks/export_fixtures`.

`PASS` means the requested checks actually ran; `--roundtrip` is required for a
target-tool gate. Without it `target_tool.status` stays `NOT_RUN`, even when the
independent reader passes. Missing pinned tools return exit 2/`NOT_RUN`; invalid
content returns exit 1/`FAIL`. Unsupported primitive modes/extensions or compressed
assets require a new tested adapter; do not reinterpret them as valid empty output.
USDZ is outside the approved initial GLB/OBJ/PLY/STL contract and is unsupported.

Current committed baseline exports contain 579 sparse points, no triangles and
arbitrary scale. They pass independent read/GLB format validation; the Blender
mesh round-trip gate deliberately refuses points. The synthetic triangle fixtures
round-trip in all four formats, including textured GLB/OBJ. These fixtures do not
prove production OBJ/STL export: their source owners061–067 have published remote serializers that require
combined-candidate integration and actual validation. Candidate generated GLB/PLY must be rerun on the integrated candidate.
No physical geometry/texture quality or complete-object claim follows.

Remaining release gates: actual candidate photographic GLB/OBJ and representation
correct PLY/STL, immutable source/artifact receipts, approved validator license
inventory and target-tool reports on those outputs. Point clouds remain point
clouds, open surfaces remain open, and metric provenance must be supplied before
a measured-unit claim is accepted. No parser can infer real-world units from raw
coordinates; expectations must bind independently known scale and axis landmarks.

## Published writer snapshots, independent matrix

`python -m tools.qa.export_interoperability.provider_matrix --output /tmp/rf090-new`
extracts immutable061–064 Git snapshots into a new external directory, serially
compiles/runs7 real native writer tests, and validates their actual output files
through Trimesh/Khronos/Blender. No other role code enters090's branch. The pinned
source SHAs are in the script and generated report. Target GLB/OBJ/STL pass actual
round-trips, coordinate bounds and photographic corner checks. The064 analytic
surface fixture fails normal/winding quality: one supplied normal points behind
its face. Its serializer preserves that input; no serializer coordinate corruption
is inferred. Request090-to-064 gives exact values and reproduction. The matrix
therefore exits1 and retains the failure. Do not silently replace the failed
fixture or treat this analytic result as physical candidate qualification.

Vertex normals may be tangent at a smooth/hard-edge transition. The quality check
allows zero face dot at a vertex only when no vertex normal points behind the
face and the face-average dot remains positive. The synthetic tangent-normal
fixture and reversed-normal rejection test exercise that distinction.
