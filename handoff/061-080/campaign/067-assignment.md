
**Role:** Android export product flow and format orchestration

**Branch:** `agents/067-export-coordinator`

**Priority:** P1

**Status receipt:** `docs/agents/status/067.md`

**Mission:** Present and execute valid export choices tied to the exact model, representation, edits and destination.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): export uses a hard-coded filename-extension whitelist and copies a selected file.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt) defines numerous separate export document launchers; there is no unified representation/format/options flow.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/export/`

**Implementation instructions:**

1. Build ExportCoordinator and typed ExportRequest from immutable artifact/revision/representation/format/options; derive availability from real completed assets.
2. Connect 061–064 writers,065 LODs,066 edit recipes,025 archives and 026 document transport with progress, cancellation and byte estimates.
3. Distinguish source bundle, diagnostics, points, observed mesh, photographic mesh and print formats; disclose units/material limitations in meaningful UI copy.
4. Support safe multi-file packages and validation before transport; failure must not mark a partial destination successful or invalidate the saved model.

**Success criteria:**

- Every displayed export option produces a valid independent-reader artifact, and unavailable representation/format combinations cannot be selected.
- A pending system-picker result stays bound to the initiating project/artifact even after navigation, process recreation or concurrent changes.
- Export-only choices reuse upstream reconstruction and preserve originals/master artifacts through cancellation and provider errors.

**Required verification:** ANDROID; DEVICE; INTEROPERABILITY; RESILIENCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 026](#agent-026); [Agent 061](#agent-061); [Agent 062](#agent-062); [Agent 063](#agent-063); [Agent 064](#agent-064); [Agent 065](#agent-065); [Agent 066](#agent-066).

**Interface handoff:** Own C12 ExportRequest/ExportOption/ExportOutcome and the app export coordinator. 001 delegates actions,073/070 present options,026 owns external I/O and 090 validates outputs.

**Start immediately:** Replace the extension whitelist concept with a typed catalog backed initially by current GLB/PLY/PNG/JSON outputs; add new serializers as they pass independent validation.
