
**Role:** Production Filament renderer and GPU lifecycle

**Branch:** `agents/069-studio-renderer`

**Priority:** P1

**Status receipt:** `docs/agents/status/069.md`

**Mission:** Implement the production model renderer with correct photographed appearance, resource control and a dependable supported fallback.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt) is an unlit GLES3 photographic viewer with bounded buffers and lifecycle tests.
- [PROJECT_PLAN.md](PROJECT_PLAN.md) specifies a production Filament studio, which is not present in app/build.gradle.kts.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt`
- `app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/viewer/filament/`
- `app/src/main/java/com/realityforge/scanner/viewer/RenderBackend.kt`

**Implementation instructions:**

1. Integrate a pinned/licensed Filament build through 004/095 and define a RenderBackend adapter around validated 068 assets.
2. Support faithful unlit photographic inspection plus clearly labeled neutral-lit geometry inspection, normals, wireframe/coverage overlays and available LODs without inventing PBR measurements.
3. Own swap-chain, surface, texture, vertex/index buffer and engine lifetime across rotation, backgrounding, context loss and repeated model changes.
4. Measure GPU/CPU residency, upload times and frame pacing; retain the verified GLES path where the production renderer is unsupported until compatibility is demonstrated.

**Success criteria:**

- Photographic reference pixels, UV orientation and geometry bounds match independent fixtures with explicit color-space handling.
- Repeated load/close/background/context-loss cycles produce no growing resource usage, stale rendering or fatal driver errors on qualified devices.
- The supported asset/profile matrix meets measured interaction budgets and falls back or refuses safely before exhausting GPU memory.

**Required verification:** ANDROID; DEVICE; COMPATIBILITY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 030](#agent-030); [Agent 061](#agent-061); [Agent 065](#agent-065); [Agent 068](#agent-068); [Agent 095](#agent-095).

**Interface handoff:** Own C14 RenderBackend and GPU resources. 070 owns controls,068 validates files,065 supplies LODs,097 measures frame/startup behavior. New libraries require 095 provenance and 004 integration.

**Start immediately:** Wrap and characterize the current GLES renderer first; implement Filament behind the same concrete asset contract and compare actual output rather than replacing working code blindly.
