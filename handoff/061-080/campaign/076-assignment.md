
**Role:** Shared design system and adaptive layout primitives

**Branch:** `agents/076-adaptive-design-system`

**Priority:** P1

**Status receipt:** `docs/agents/status/076.md`

**Mission:** Give the app a consistent, responsive visual system that remains readable and operable across supported Android form factors.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):35-40 hard-codes one dark palette; layout is a single full-width LazyColumn.
- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):52-74 and ui/PhotographicModelViewer.kt:44-63 each define ad hoc sizing and text arrangements.

**Exclusive existing files:**

- `app/src/main/res/values/styles.xml`
- `app/src/main/res/drawable/ic_launcher.xml`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/designsystem/`
- `app/src/main/res/values/design_system.xml`
- `docs/agents/status/076.md`
- `app/src/test/java/com/realityforge/scanner/agents/a076/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a076/`

**Implementation instructions:**

1. Define semantic typography, color, spacing, shape and state tokens with system light/dark support and accessible contrast.
2. Implement reusable screen scaffolds, status/error/progress components, labelled controls and responsive width/column policies without editing feature screens.
3. Provide clear interaction states for destructive actions, partial results, unknown evidence and disabled capabilities; avoid decorative elements that imply unavailable scan coverage.
4. Coordinate migrations through 071/014/070/072–079 and resource owner 078, keeping APIs small and documenting only necessary usage contracts.
5. Integrate the real Android theme/style and launcher resources you own with 001; keep visual assets lightweight, licensed and consistent with actual product capabilities.

**Success criteria:**

- Representative screens render without clipped actions/text at small-phone, landscape and tablet sizes with 100% and 200% font scales.
- Contrast/touch-target checks pass for documented token states in light/dark themes and error/disabled/selection variants.
- Integrated feature screens consume the shared components without duplicate theme definitions or visual state disagreement.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Adaptive layout/theme screenshot review.

**Integration inputs and cooperating owners:** [Agent 078](#agent-078).

**Interface handoff:** Expose AppTheme and focused design components to screen owners; 071 alone replaces the old ScannerApp palette. Own only designsystem code and uniquely named design resources.

**Start immediately:** Implement reusable tokens/components and representative state previews now; screen owners can adopt them while final integrated screenshot checks run later.
