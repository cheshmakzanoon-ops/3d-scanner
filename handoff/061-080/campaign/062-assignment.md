
**Role:** OBJ and MTL photographic export

**Branch:** `agents/062-obj-export`

**Priority:** P1

**Status receipt:** `docs/agents/status/062.md`

**Mission:** Export useful OBJ packages with valid geometry, material references and the actual photographic atlas.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md): GLB/OBJ/PLY/STL exports are part of the product contract; current ScannerViewModel.export whitelist has no OBJ/MTL.
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): photographic triangle/UV/atlas data already exists and can be exported without reconstructing it again.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/obj_export.hpp`
- `reconstruction/src/obj_export.cpp`

**Implementation instructions:**

1. Consume 061 asset data, writing stable vertex/UV/normal indices and correct face winding with a documented coordinate/units convention.
2. Write MTL and source-texture references with safe relative filenames; use a package/ZIP manifest through 067 for providers that cannot create sibling documents.
3. Stream large text output with cancellation and output budgets; preserve holes, disconnected observed components and photometric provenance.
4. Keep sparse point export explicit and unsupported combinations rejected; do not silently convert points into faces.

**Success criteria:**

- An independently imported OBJ package has the expected triangle count, bounding box, normals, UV orientation and photographed appearance.
- Texture filenames resolve after ZIP extraction into a different directory, with no external path or missing sibling resource.
- Cancellation, short writes and large-model limits produce a clear failed export without changing the current project artifact.

**Required verification:** HOST; ANDROID; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 061](#agent-061); [Agent 067](#agent-067).

**Interface handoff:** Provide a descriptor plus file-set writer to 067; 026 owns document-provider transport. 090 validates in an independent consumer, including a checker texture and asymmetric geometry.

**Start immediately:** Implement against hand-constructed canonical asset fixtures and current photographic outputs; request shared build registration early.
