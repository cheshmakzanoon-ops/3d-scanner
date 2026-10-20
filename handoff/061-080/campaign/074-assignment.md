
**Role:** Processing progress, pause and recovery screen

**Branch:** `agents/074-processing-recovery-ui`

**Priority:** P0

**Status receipt:** `docs/agents/status/074.md`

**Mission:** Present the durable pipeline truthfully so users can pause, return, recover and inspect previous results without losing track of their scan.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 154-160 shows only stage count, progress bar and Stop at safe boundary.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): 185-201 reduces interruption to a notice/state; no durable queued/retry/resume/previous-result experience exists.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/processing/`
- `docs/agents/status/074.md`
- `app/src/test/java/com/realityforge/scanner/agents/a074/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a074/`

**Implementation instructions:**

1. Define UI states for queued, running, paused, interrupted, failed, cancelled and completed jobs using owner 007/008 events and persisted identity.
2. Render measured stage counts, real cache reuse and resource constraints; use indeterminate progress when totals or timing are unknown.
3. Wire pause/resume/retry/cancel/open-previous-result actions with safe enablement, versioned input/settings display and notification permission/recovery routes.
4. Integrate structured errors and suggested corrections, keeping detailed technical evidence accessible without overwhelming the main progression.

**Success criteria:**

- A real background/process-killed job reconnects to matching persisted state after reopen and offers valid resume/retry actions without duplicate work.
- Displayed progress/cached stages/status match actual execution events; missing stages and unknown time never become fabricated completion percentages.
- Rapid repeated actions, changed inputs, rotation and notification entry cannot operate on a stale job or discard previous completed output.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 interrupted/background reconstruction UI flow.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 010](#agent-010); [Agent 023](#agent-023); [Agent 024](#agent-024); [Agent 029](#agent-029); [Agent 030](#agent-030); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose ProcessingScreen(JobUiState, JobAction) to 071; 007/008 own durable execution and 001 validates user commands. Render 029 permission/action state without creating services.

**Start immediately:** Implement state rendering and action-availability tests using real-shaped baseline events now; finalize against actual durable execution and kill/restart instrumentation.
