
**Role:** Actual Android UI end-to-end and interruption tests

**Branch:** `agents/080-actual-ui-end-to-end`

**Priority:** P0

**Status receipt:** `docs/agents/status/080.md`

**Mission:** Verify the complete user-visible app through actual UI interactions, document providers and process/lifecycle interruptions on the final integrated code.

**Why this work remains:**

- [app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt](app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt):39-112 exercises the actual ViewModel/pipeline but invokes controller actions directly for key steps.
- [app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt](app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt):16-62 reuses persisted files within a test, rather than killing/relaunching the full application.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/androidTest/java/com/realityforge/scanner/uiflow/`
- `tools/android-ui-e2e/`
- `docs/agents/status/080.md`

**Implementation instructions:**

1. Add deterministic UI fixtures that consume 088's shared test document provider using explicitly synthetic photographs; traverse create/import/review/process/studio/export/reopen through real controls.
2. Cover permission denial/regrant, late camera callbacks, changed inputs, cancellation, repeated actions, rotation, background/lock and external picker return with stable operation identity.
3. Build UI scenario adapters over 088's shared external process-kill/relaunch controller and assert artifact/revision outcomes; coordinate CI/dependency registration with 004 without duplicating the controller or editing shared build files.
4. Add physical-device capture flow entrypoints and evidence collection without labelling synthetic/emulator success as real-world geometry or thermal validation.

**Success criteria:**

- The integrated real UI imports/reconstructs/views/exports/reopens known fixtures with exact exported/original hashes and no uncaught crash or stale result.
- Process-kill/picker/camera/provider fault scenarios recover to valid persisted state, preserving originals and previous completed output without duplicate publication.
- Head-specific test reports contain device/API/ABI, commands, failures and genuine screenshots; unsupported hardware gates remain explicitly pending.

**Required verification:** ./gradlew :app:assembleDebug :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; External process-kill/relaunch UI gate; Physical camera/permission/user-flow gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 004](#agent-004); [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 011](#agent-011); [Agent 014](#agent-014); [Agent 018](#agent-018); [Agent 023](#agent-023); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 026](#agent-026); [Agent 027](#agent-027); [Agent 029](#agent-029); [Agent 030](#agent-030); [Agent 067](#agent-067); [Agent 070](#agent-070); [Agent 071](#agent-071); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Own UI-flow tests/orchestration and evidence only; request product fixes from assigned file owners. Consume 077 accessibility helpers while independently proving full action-to-artifact paths. 088 owns shared fault injection and process-control infrastructure; 080 owns the real UI scenarios that consume it.

**Start immediately:** Automate the existing baseline UI and capture its regressions now; expand continuously as feature branches integrate and rerun against the final head.
