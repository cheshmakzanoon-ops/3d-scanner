
**Role:** STL export and printability diagnostics

**Branch:** `agents/063-stl-export`

**Priority:** P1

**Status receipt:** `docs/agents/status/063.md`

**Mission:** Offer an honest, interoperable triangle-only STL export with explicit scale and topology limitations.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md) requires STL where appropriate; current code provides no STL writer.
- [README.md](README.md): geometry has arbitrary scale and may be nonmanifold/open; a print-ready promise would be false.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/stl_export.hpp`
- `reconstruction/src/stl_export.cpp`

**Implementation instructions:**

1. Implement bounded binary STL writing from 061 surface assets with correct facet normals, face count and orientation.
2. Require explicit units or an acknowledged arbitrary-scale conversion through 066; preserve the stored master geometry and disclose STL material/color limitations.
3. Use 055 topology results to report open/nonmanifold meshes; never automatically fill holes or claim printability from successful file writing.
4. Integrate format eligibility and output size estimation into 067 with cancellation, finite-value checks and external parser tests.

**Success criteria:**

- An independent STL parser reports identical triangle count and the selected scale/bounds for an asymmetric reference fixture.
- Point clouds and missing/invalid scale choices receive clear appropriate handling, and an open mesh remains identified as open.
- Invalid normals/coordinates/count overflow and interrupted writes cannot be published as successful STL exports.

**Required verification:** HOST; ANDROID; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 055](#agent-055); [Agent 061](#agent-061); [Agent 066](#agent-066); [Agent 067](#agent-067).

**Interface handoff:** Consume canonical observed surface data and EditRecipe scale; expose printability diagnostics to 067/074. Do not add a repair pipeline in the serializer.

**Start immediately:** Build the writer and topology/scale cases on local analytic fixtures immediately; add real meshes when 053/055 are available.
