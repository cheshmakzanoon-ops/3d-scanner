
**Role:** Detail-preserving mesh decimation and LODs

**Branch:** `agents/065-mesh-lod`

**Priority:** P1

**Status receipt:** `docs/agents/status/065.md`

**Mission:** Create smaller view/export variants with measured geometric and photographic error while preserving the highest-fidelity source.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md): mesh optimization/LODs and rebaking are open product phases.
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp) builds observed patches; there is no decimation/LOD module in reconstruction/src.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/mesh_lod.hpp`
- `reconstruction/src/mesh_lod.cpp`

**Implementation instructions:**

1. Implement constrained decimation that preserves open boundaries, disconnected components, material/UV seams and thin structures.
2. Generate deterministic LOD recipes from explicit face/error budgets; reject reductions that break topology constraints or invert faces.
3. Keep master geometry immutable and label every derived LOD with parent identity, algorithm version and measured deviation.
4. Coordinate optional rebake/UV transfer with 057/058 and expose LOD selection to 067/069; measure geometric error independently with 084.

**Success criteria:**

- Requested reductions meet predeclared geometric error bounds on closed, open, thin and disconnected fixtures without invented surface closure.
- UV/normal/face integrity and texture seams remain valid after reduction, or the operation refuses an unsupported case.
- LOD reuse/invalidation follows 009 identities; master counts/hashes remain unchanged and cancellation never replaces the master.

**Required verification:** HOST; GEOMETRY; TEXTURE; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 055](#agent-055); [Agent 057](#agent-057); [Agent 058](#agent-058); [Agent 061](#agent-061).

**Interface handoff:** Provide an immutable derived SurfaceAsset plus error report to 067 and 069.055 sets topology invariants,057 handles any rebaking, and 009 identities distinguish every LOD.

**Start immediately:** Develop constrained simplification and boundary tests on existing open mesh fixtures; use actual reduced geometry rather than a renderer-only triangle skip.
