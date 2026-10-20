
**Role:** Interactive studio controls and diagnostic viewers

**Branch:** `agents/070-studio-controls`

**Priority:** P1

**Status receipt:** `docs/agents/status/070.md`

**Mission:** Make all actual scan representations inspectable through responsive, accessible controls and truthful diagnostic overlays.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt) provides orbit/pinch/reset in a full-screen dialog; PointCloudViewer.kt and SurfaceMeshViewer.kt are separate diagnostic paths.
- [PROJECT_PLAN.md](PROJECT_PLAN.md) requires local model inspection and crop/orient/scale with production usability.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt`
- `app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt`
- `app/src/main/java/com/realityforge/scanner/ui/SurfaceMeshViewer.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/studio/`

**Implementation instructions:**

1. Integrate 068/069 model loading/rendering behind loading/failed/ready states; preserve point and untextured-mesh inspection when photographic output is unavailable.
2. Add predictable orbit/pan/zoom, fit/reset, orientation presets, clipping/section inspection where supported and clear representation switches.
3. Connect 066 non-destructive edit/measurement controllers,065 LOD selection and 067 export options without editing master data directly.
4. Provide buttons and semantic actions alongside gestures; handle insets, tablets, keyboard/accessibility navigation and lifecycle cancellation with 071/076/077/078.

**Success criteria:**

- Users can inspect sparse, dense, observed mesh and photographic output without confusing them, including unsupported/failed-renderer cases.
- Camera controls and edits remain stable through resizing/reopen; measured scale and unknown scale stay visibly distinct.
- Accessible controls perform the same essential actions as gestures, and repeated studio sessions pass lifecycle/performance tests.

**Required verification:** ANDROID; DEVICE; ACCESSIBILITY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 065](#agent-065); [Agent 066](#agent-066); [Agent 067](#agent-067); [Agent 068](#agent-068); [Agent 069](#agent-069); [Agent 071](#agent-071); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Own C15 studio interaction state and the three existing viewer Composables. 069 only renders,066 only owns edit logic,071 handles navigation and 077 supplies semantics/testing guidance.

**Start immediately:** Improve and test controls against the current GLES/diagnostic viewers first; accept renderer/editor interfaces as additive providers so UI work begins immediately.
