
**Role:** PLY export with normals, confidence and provenance

**Branch:** `agents/064-ply-export`

**Priority:** P1

**Status receipt:** `docs/agents/status/064.md`

**Mission:** Preserve useful scan evidence in interoperable point and surface PLY exports.

**Why this work remains:**

- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): writePly embeds sparse_points semantics in a shared result writer.
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp) and surface.cpp recover confidence and surface observations, but the export contract does not consistently retain them.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/ply_export.hpp`
- `reconstruction/src/ply_export.cpp`

**Implementation instructions:**

1. Implement explicit sparse, dense and surface variants with vertex colors, optional normals, confidence/support fields and faces where appropriate.
2. Support a documented bounded binary encoding and a small readable fixture path, with stable property order/types and checked byte counts.
3. Carry unit/coordinate/observed-representation metadata without claiming standard readers understand custom comments; preserve unknown scale.
4. Route legacy writers through their owners 037/053 and expose choices through 067; coordinate normal conventions with 054 and GLB transforms with 061.

**Success criteria:**

- Independent readers recover expected points/faces, colors, normal directions, confidence fields and bounds from roundtrip fixtures.
- Dense outputs are never labeled sparse; surface exports carry actual face indices and sparse exports never fabricate faces.
- Malformed geometry, unsupported field sizes and cancellation produce explicit errors with bounded memory.

**Required verification:** HOST; ANDROID; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 037](#agent-037); [Agent 053](#agent-053); [Agent 054](#agent-054); [Agent 061](#agent-061); [Agent 067](#agent-067).

**Interface handoff:** Provide the PLY serializer and metadata descriptor to 067.061 owns shared representation/coordinate policy,054 supplies normals, and 090 verifies externally.

**Start immediately:** Reproduce current point semantics with existing export fixtures and add a tiny independent property parser before integrating with the app.
