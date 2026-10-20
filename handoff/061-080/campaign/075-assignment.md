
**Role:** Offline onboarding and practical capture instructions

**Branch:** `agents/075-capture-onboarding-instructions`

**Priority:** P1

**Status receipt:** `docs/agents/status/075.md`

**Mission:** Explain the supported offline scanning workflow clearly enough that a new user can gather useful photographs and understand unsupported outcomes.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):54-55 offers two short capture sentences; ScannerApp.kt: 130 prints checkerboard instructions inline.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):98,142,207,306 exposes mixed milestone/development text rather than a coherent new-user capture guide.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/onboarding/`
- `app/src/main/assets/onboarding/`
- `docs/agents/status/075.md`
- `app/src/test/java/com/realityforge/scanner/agents/a075/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a075/`

**Implementation instructions:**

1. Create a short skippable, revisitable guide for choosing a rigid matte textured subject, moving around it with overlap, preserving optics and reviewing poor photos.
2. Explain calibration, arbitrary versus measured scale, unobserved/reflective/transparent surfaces and partial reconstruction in plain language tied to actual capabilities.
3. Provide offline checkerboard instructions/assets derived from the existing approved board, with correct inner-corner dimensions and no implied real-scale guarantee.
4. Integrate context-specific help with 014/017/027/073 and persist onboarding preferences through 079; avoid network links as a prerequisite for scanning.

**Success criteria:**

- An offline fresh-install walkthrough reaches a real capture/import session, and every instruction/help asset remains available in airplane mode.
- Content validation checks board geometry, capability descriptions and scale/coverage wording against implemented feature contracts and current evidence.
- Large text, TalkBack, Back/skip/revisit and process recreation make the guide usable without trapping or delaying returning users.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Offline fresh-install usability/content review.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 014](#agent-014); [Agent 017](#agent-017); [Agent 027](#agent-027); [Agent 073](#agent-073); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Expose OnboardingScreen and context-help components/action IDs to 071/014/073; 079 owns preference persistence and 027 owns calibration acceptance truth.

**Start immediately:** Draft offline help states/assets from PROJECT_PLAN.md and actual current capabilities now; reconcile all promised features with integrated implementation before acceptance.
