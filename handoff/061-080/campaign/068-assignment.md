
**Role:** Bounded model loading and GLB parser hardening

**Branch:** `agents/068-model-loader`

**Priority:** P0

**Status receipt:** `docs/agents/status/068.md`

**Mission:** Keep model loading safe and correct as exported geometry, atlases and studio capabilities grow.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt) already bounds file/image size, checks PNG CRC and rejects external model data, but supports a restricted in-memory asset subset.
- [app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt) reads the entire accepted asset before viewing; production profiles and richer exported assets need coordinated loader budgets.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/viewer/loading/`

**Implementation instructions:**

1. Document the supported GLB contract and validate chunks/accessor ranges, strides, indices, dimensions, PNG structure and integer arithmetic before allocation.
2. Add bounded staged or streaming reads where useful; budget CPU image/geometry copies and decoded GPU upload data together with 030/069.
3. Maintain explicit rejection of external URI resolution and unsupported extensions; do not silently drop primitives, materials or transforms.
4. Expose immutable validated assets/errors through 061 representation adapters and exercise parser failures with 082 fuzz corpora.

**Success criteria:**

- Generated current and new 061 GLBs load with identical geometry, UV orientation and image dimensions within the supported contract.
- Malformed/truncated/oversized/overlapping buffers and decompression-bomb inputs fail without crash, unbounded allocation or external access.
- Repeated open/cancel/close cycles release CPU assets and integrate with renderer lifecycle; supported maximum assets stay within measured budget.

**Required verification:** ANDROID; DEVICE; FUZZ; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 030](#agent-030); [Agent 061](#agent-061); [Agent 082](#agent-082).

**Interface handoff:** Own C13 ValidatedAsset loading and PhotographicGlb. 069 consumes validated buffers and 070 owns UI; parser security changes are implemented here even when found by 082/096.

**Start immediately:** Use the existing parser and viewer fixtures for a malformed-input corpus and profile current allocation paths before expanding support.
