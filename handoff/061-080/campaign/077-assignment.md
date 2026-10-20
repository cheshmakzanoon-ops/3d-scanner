
**Role:** Accessibility semantics and complete-flow verification

**Branch:** `agents/077-accessibility-verification`

**Priority:** P0

**Status receipt:** `docs/agents/status/077.md`

**Mission:** Make the actual capture/review/processing/studio/export workflow operable with assistive technologies and verify beyond basic label presence.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt):39, ui/SurfaceMeshViewer.kt:65 and ui/PhotographicModelViewer.kt: 111 have descriptions but no accessible orbit/zoom equivalents.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 251-254 displays a separate switch and Wireframe text; progress/focus/large-text workflows lack dedicated accessibility tests.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/accessibility/`
- `docs/agents/status/077.md`
- `app/src/test/java/com/realityforge/scanner/agents/a077/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a077/`

**Implementation instructions:**

1. Provide reusable semantic actions/state descriptions, grouped labelled controls and focus/announcement helpers for progress, errors and viewer interactions.
2. Define a screen-by-screen audit covering TalkBack, keyboard/switch access, zoom/orbit alternatives, touch targets, contrast, large fonts and RTL.
3. Implement dedicated accessibility instrumentation and submit exact scoped remediation requests to each screen owner; do not edit their files or mark gaps resolved by a helper alone.
4. Verify focus after navigation/dialogs, lifecycle recovery and dynamic progress changes, ensuring announcements are useful rather than continuously disruptive.

**Success criteria:**

- The integrated app's primary offline scan/export path completes through accessibility actions without requiring inaccessible pinch/drag-only gestures.
- Automated semantic/layout checks and a recorded TalkBack/keyboard review pass at 200% text with clear errors and destructive confirmations.
- Every remediation request is integrated by its owner and rechecked against the actual screen; unresolved hardware/manual evidence remains explicitly open.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Manual TalkBack/keyboard/switch-access device gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 014](#agent-014); [Agent 028](#agent-028); [Agent 070](#agent-070); [Agent 071](#agent-071); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Expose semantic helpers/test assertions and an evidence-backed remediation ledger; feature owners retain their source files. Coordinate with 080 for flows while owning accessibility-specific verification.

**Start immediately:** Audit existing semantics and implement baseline accessibility tests immediately; execute final acceptance after real screens and viewer controls are wired.
