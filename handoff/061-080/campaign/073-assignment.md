
**Role:** Photo review, project detail and recapture actions

**Branch:** `agents/073-photo-review-project-ui`

**Priority:** P0

**Status receipt:** `docs/agents/status/073.md`

**Mission:** Make photo review an actionable stage where users inspect images, curate reconstruction inputs, understand failures and prepare a valid scan.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 338-352 PhotoRow is a 72 dp thumbnail plus metadata/exposure text without zoom or curation actions.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 125-160,167-197 mixes calibration/processing/evidence cards but does not link bad photos to corrective selection or retakes.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/photoreview/`
- `docs/agents/status/073.md`
- `app/src/test/java/com/realityforge/scanner/agents/a073/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a073/`

**Implementation instructions:**

1. Build a bounded thumbnail grid/list and full-photo review with zoom, source metadata, measured blur/exposure and known calibration compatibility.
2. Integrate include/exclude/reorder/remove/undo actions from 018 with clear original-preservation semantics and immediate selected-input count updates.
3. Present calibration workflow/results from 027 and existing structured diagnostics with links to affected photos/cameras and meaningful recapture actions.
4. Launch processing, capture and viewers through 071/001/070; do not duplicate decoder buffers, reconstruction state or numerical quality heuristics.

**Success criteria:**

- A real mixed-photo project can inspect/exclude/reorder poor inputs and reconstruct using exactly the resulting persisted selection.
- Calibration/quality failures identify concrete affected inputs or unknown evidence without claiming complete object coverage from texture statistics.
- Large images/collections, rotation and repeated review navigation remain within measured thumbnail/bitmap budgets and expose all actions accessibly.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 photo curation/calibration/reconstruction flow.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 012](#agent-012); [Agent 013](#agent-013); [Agent 018](#agent-018); [Agent 024](#agent-024); [Agent 027](#agent-027); [Agent 070](#agent-070); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose ProjectReviewScreen with typed photo/quality/calibration state/actions; 018 performs curation, 027 calibrates, 024 supplies verified results and 071 owns routes.

**Start immediately:** Implement photo review against baseline ScanPhoto/diagnostic adapters and bitmap-lifetime tests now; replace adapters with integrated typed state before final acceptance.
