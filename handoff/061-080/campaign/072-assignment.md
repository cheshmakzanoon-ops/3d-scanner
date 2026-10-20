
**Role:** Production project library screen

**Branch:** `agents/072-project-library-ui`

**Priority:** P1

**Status receipt:** `docs/agents/status/072.md`

**Mission:** Provide a usable offline project library that accurately represents scan availability, progress and recoverable failures as collections grow.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):91-112 contains only create plus createdAt-sorted basic cards with state/message/date.
- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):79-83 creates named projects but existing UI has no search, rename, duplicate or recoverable cleanup state.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/library/`
- `docs/agents/status/072.md`
- `app/src/test/java/com/realityforge/scanner/agents/a072/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a072/`

**Implementation instructions:**

1. Implement bounded project-list rendering, loading/empty/error states and search/filter/sort controls consuming owner 019 queries.
2. Add create/rename/duplicate/delete actions with validation, progress and confirmation appropriate to preserved originals and pending deletion.
3. Show meaningful result representations, selected photo counts, last-completed availability and interrupted/failed jobs without exposing internal milestone labels as primary state.
4. Wire open/restore/import-archive actions through 071/001 and provide accessible feature-prefixed resources and design-system components.

**Success criteria:**

- Large project fixtures scroll/filter/sort within a declared UI frame budget and maintain stable item identity and restored position.
- Actual create/rename/duplicate/delete/reopen actions update persisted library state, and editing/deleting one duplicate leaves the other unchanged.
- TalkBack, keyboard, large text and rotation preserve clear destructive scope, recoverable errors and access to previous completed results.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 library management flow.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 019](#agent-019); [Agent 022](#agent-022); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose LibraryScreen(LibraryUiState, LibraryAction) to 071; 019 owns queries/commands, 022 owns deletion, 001 routes operations. Own only ui/library and prefixed resources.

**Start immediately:** Build library composition and interaction tests with representative immutable states now; integrate real repository flows and commands before completion.
