
**Role:** Preferences, supported profiles and about/privacy screen

**Branch:** `agents/079-preferences-about-privacy-ui`

**Priority:** P1

**Status receipt:** `docs/agents/status/079.md`

**Mission:** Provide reliable local preferences and clear capability/privacy information tied to the app actually shipped.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):59 keeps fallback focal length only in rememberSaveable; no durable user-preferences/settings screen exists.
- [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) and res/xml/data_extraction_rules.xml enforce offline/no-backup defaults that need accurate user-facing explanation.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/preferences/`
- `app/src/main/java/com/realityforge/scanner/ui/preferences/`
- `docs/agents/status/079.md`
- `app/src/test/java/com/realityforge/scanner/agents/a079/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a079/`

**Implementation instructions:**

1. Define versioned local user preferences for approved processing/display defaults, onboarding and accessibility-compatible settings, with safe migration and validation.
2. Expose only profiles supported by owner 030/engine evidence; changing defaults must not silently mutate an active project's recorded reconstruction settings.
3. Build settings/about/privacy surfaces showing current version, offline behavior, original preservation/uninstall consequences, explicit provider exports and notices links/assets.
4. Route storage management, calibration help and diagnostic views through 028/075/071; preserve no-network/no-analytics defaults and keep owner-managed licensing notices authoritative.

**Success criteria:**

- Preferences survive process recreation/update, reject corrupt/unsupported values and keep active job settings/identity unchanged.
- Fresh-install/airplane-mode settings/about/privacy routes function entirely locally and describe actual manifest/backup/export behavior accurately.
- Profile/settings selection is accessible, localized and reflected in the next actual operation without claiming unsupported quality or background behavior.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Offline preferences persistence/privacy consistency gate.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 028](#agent-028); [Agent 030](#agent-030); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose PreferencesStore and PreferencesScreen to 001/071/075; 009 snapshots chosen settings into input identity, 030 supplies supported plans. Build/notice owners supply authoritative version/licenses.

**Start immediately:** Implement small validated preference schemas and baseline about/privacy content now; integrate real supported-profile and storage routes before completion.
