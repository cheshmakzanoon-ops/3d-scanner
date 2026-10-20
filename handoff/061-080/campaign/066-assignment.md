
**Role:** Non-destructive orientation, crop, scale and measurements

**Branch:** `agents/066-model-editing`

**Priority:** P1

**Status receipt:** `docs/agents/status/066.md`

**Mission:** Let users make useful local edits and measurements through reversible recipes with explicit scale provenance.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md): production studio requires crop/orient/scale; current viewer only orbits, zooms and resets.
- [README.md](README.md) and docs/ARCHITECTURE.md disclose arbitrary scale and open observed geometry.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/studio/editing/`
- `reconstruction/include/realityforge/asset_edit.hpp`
- `reconstruction/src/asset_edit.cpp`

**Implementation instructions:**

1. Define versioned EditRecipe operations for orientation, crop/component selection, metric reference and undo/redo, stored separately from the source asset.
2. Implement coordinate-correct transforms, normal/winding updates and conservative crop behavior that preserves open boundaries and photographic UV correspondence.
3. Require a real known-distance reference before claiming metric accuracy; propagate units and uncertainty into diagnostics and exports.
4. Persist edit state through 005/009, connect controls through 070 and export through 067 without rerunning camera alignment for a view/edit change.

**Success criteria:**

- Undo/redo and reopening reproduce exact edit recipes while source geometry/photo hashes remain unchanged.
- Known-distance synthetic fixtures yield the expected scale/measurement and axis orientation; unknown scale is never displayed as measured meters.
- Crop/transform outputs pass topology and external-format checks, including negative/reflected transforms if supported; unsupported operations fail explicitly.

**Required verification:** HOST; ANDROID; DEVICE; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 009](#agent-009); [Agent 055](#agent-055); [Agent 061](#agent-061).

**Interface handoff:** Own C11 EditRecipe/MeasurementEvidence and its pure geometry operations. 070 renders controls,005 persists recipes and 067 materializes selected variants; no direct edits to viewer or database owner files.

**Start immediately:** Build reversible edit/coordinate tests on existing assets and provide a controller that the current viewer can adopt without a renderer migration.
