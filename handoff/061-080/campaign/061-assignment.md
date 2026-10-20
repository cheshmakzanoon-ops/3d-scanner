
**Role:** Canonical 3D asset model and correct GLB export

**Branch:** `agents/061-glb-asset-contract`

**Priority:** P0

**Status receipt:** `docs/agents/status/061.md`

**Mission:** Give points, observed meshes and photographic assets one explicit representation contract and export standards-conforming GLB without semantic mislabeling.

**Why this work remains:**

- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): writePly/writeGlb label rf::Result as sparse_points even when the dense-point export path in SparsePipeline.run supplies dense points.
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp) and surface.cpp contain separate mesh/GLB writers; representation and buffer conventions need a shared validated boundary.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/asset_model.hpp`
- `reconstruction/include/realityforge/glb_export.hpp`
- `reconstruction/src/asset_model.cpp`
- `reconstruction/src/glb_export.cpp`

**Implementation instructions:**

1. Define validated PointAsset/SurfaceAsset/TexturedAsset views with indices, normals, UVs, source provenance, arbitrary-versus-metric units and no accidental geometry copies.
2. Implement bounded GLB serialization for each representation, correct coordinate/winding transforms, byte lengths/alignment, material/image references and finite accessor bounds.
3. Make sparse and dense point semantics distinct in embedded metadata; never use POINTS to advertise a surface or mark vertex color as a photographic UV texture.
4. Have 037/053/057 delegate legacy writers through small compatibility adapters; 003 exposes the typed choice and 067 builds available format options. Use independent 090 validation.

**Success criteria:**

- Sparse POINTS, dense POINTS, observed triangles and textured triangles retain correct representation after external import.
- GLB lengths/indices/accessors/materials pass an independent validator; opposite-axis fixtures prove winding and UV orientation.
- Oversized/nonfinite/invalid input is rejected before large allocation or output publication; export is cancellable and preserves original geometry.

**Required verification:** HOST; ANDROID; DEVICE; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 003](#agent-003); [Agent 009](#agent-009); [Agent 037](#agent-037); [Agent 053](#agent-053); [Agent 057](#agent-057).

**Interface handoff:** Own C10 canonical asset representation and GLB serializer. Domain owners retain their source files and call this module; 062–066,068–070 and 090 consume the same declared geometry/units.

**Start immediately:** Build independent serialization fixtures from current point/mesh output and reproduce the sparse-versus-dense label issue before wiring legacy writers.
