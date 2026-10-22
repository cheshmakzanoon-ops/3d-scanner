# UI instrumentation repair (not device qualification)

Observed hosted API35 x86_64 failures in 38599 diagnostics:
- a071: settings popup does not inherit Scaffold testTagsAsResourceId; propagate explicitly at DropdownMenu root.
- a072/a077: editable owner has no direct description while its own virtual child has the localized description. Require label/hint on that owner or its immediate noneditable child, retaining real ACTION_SET_TEXT and callback checks.
- a074: role-only virtual Button child reports enabled while operational clickable parent is disabled. Resolve clickable owner, not class name; preserve disabled/repeated dispatch assertions.
- a076: Text label and checkable toggle owner are distinct raw accessibility nodes. Resolve nearest checkable/clickable owner bounded by fixture and deduplicate; retain exactly one named owner, target geometry and actual checked-state/callback checks.
- a073: wait for unique replacement review fixture and bounded real accessibility scrolling before resolving reconstruction label. No synthetic click/controller bypass.
- uiflow: create-dialog transition races sole-editable fallback, which can type into library Search. Require associated Project name field in app, retain sole-field path only for DocumentsUI, reacquire rejected actions, wait text readback and enabled click owner.

AndroidX bridge explicitly emits fake description/role children for merging semantics nodes; direct raw-node co-location is not the Android accessibility contract:
https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt

Verification: git diff --check passed. No Gradle compilation or new device execution performed by this worker. The seven changed files require actual compilation/instrumentation on new candidate APKs. No production state/repository mutation logic changed; only production edit is popup test-tag propagation.
