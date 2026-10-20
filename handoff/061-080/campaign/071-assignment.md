
**Role:** Application navigation and feature composition

**Branch:** `agents/071-app-navigation-composition`

**Priority:** P0

**Status receipt:** `docs/agents/status/071.md`

**Mission:** Turn the existing app shell into a coherent, fully wired capture-to-export flow while preserving all real implemented functionality.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):44-325 contains library, capture, calibration, processing, diagnostics, viewers, export launchers and destructive dialogs in one function.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):53-74 saves UI booleans but pending picker requests and selected project have no common durable route/operation model.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/navigation/`
- `docs/agents/status/071.md`
- `app/src/test/java/com/realityforge/scanner/agents/a071/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a071/`

**Implementation instructions:**

1. Define stable project-aware routes for library, capture, review, processing/recovery, studio, export and settings with restoration/back-navigation behavior.
2. Extract screen-specific composition by delegating to owners 072–079/028/014/070; retain only shared shell/navigation/activity-result integration in ScannerApp.
3. Bind document/camera callbacks to owner 001 immutable pending requests; prevent stale results, duplicate launches and loss of operation identity during recreation.
4. Integrate feature screens, actual services, shared design, localization and accessibility; move engineering milestone prose into appropriate diagnostics/development context.

**Success criteria:**

- The real app completes create/import/capture/review/reconstruct/studio/export/reopen through wired routes with no dead buttons or fixture-only screens.
- Back, rotation, process recreation and returning activity results preserve correct project/operation identity without opening an unrelated model.
- All migrated baseline capabilities remain reachable and regression-tested; route navigation and user-facing state match persisted operations.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 integrated UI end-to-end gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 014](#agent-014); [Agent 024](#agent-024); [Agent 028](#agent-028); [Agent 067](#agent-067); [Agent 070](#agent-070); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Own only root composition/routes and launcher plumbing; accept immutable screen state/actions from 001 and feature composables from designated owners. Never duplicate repository/work execution.

**Start immediately:** Map existing routes/actions and implement the navigation adapter with baseline screens now; progressively replace them with real feature implementations before final verification.
