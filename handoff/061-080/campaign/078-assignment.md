
**Role:** Localization resources and structured user messages

**Branch:** `agents/078-localization-resources`

**Priority:** P1

**Status receipt:** `docs/agents/status/078.md`

**Mission:** Make all shipped user-facing content localizable with correct formatting, plurals, locale behavior and accessible wording.

**Why this work remains:**

- [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml) contains only app_name and an outdated M01 milestone label.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt), ui/CameraScreen.kt and ScannerViewModel.kt hard-code nearly all user text, formatting and failure messages in English.

**Exclusive existing files:**

- `app/src/main/res/values/strings.xml`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/localization/`
- `app/src/main/res/values/localization.xml`
- `docs/agents/status/078.md`
- `app/src/test/java/com/realityforge/scanner/agents/a078/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a078/`

**Implementation instructions:**

1. Define stable feature-prefixed resource-key conventions and a structured user-message resolver compatible with owner 010 errors/evidence.
2. Migrate common resources and supply exact replacement instructions to source owners; each feature owns its unique English resource XML, avoiding shared-file collisions.
3. Handle plural counts, measurement/date/number formatting and text direction; remove stale milestone text from normal UI without concealing technical diagnostics.
4. Enable pseudo-locale/RTL verification through build owner 004 and add completeness/format checks; do not require invented translations or claim unreviewed language support.

**Success criteria:**

- A resource/source audit finds no unjustified hard-coded user-facing sentences in integrated primary screens or transient operation errors.
- Pseudo-localized long text and RTL runs show no missing resources, format crashes, clipped critical controls or reversed measurement meaning.
- Structured errors preserve technical evidence separately while resolving actionable localized UI messages consistently across screens/notifications.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Pseudo-locale/RTL and resource completeness gate.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 010](#agent-010).

**Interface handoff:** Expose UserMessageResolver and formatting/key contracts to all UI, notification and controller owners. Own original strings.xml/common localization resources; feature-prefixed files remain with their feature owners.

**Start immediately:** Inventory baseline strings and define key/message contracts now so feature agents can author compliant resources immediately; verify the final merged app later.
