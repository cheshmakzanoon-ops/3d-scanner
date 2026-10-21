# Adapter-only unit and compile-stage draft — NOT_RUN

These artificial geometry/observation fixtures test ONLY conversion/validation logic. They never enter the actual-photo runtime experiment. The test file uses Robolectric JSON but performs no native solver call. It compiles alongside the frozen profile-pair sources, whose main/helper hashes must be checked before this gate.

Initial14 cases cover positive support/threshold, deterministic photo-list order, zero sharpness, negative/nonfinite sharpness, foreign/duplicate photos, wrong pointID/XYZ/pose, and refined-intrinsics rejection. Foreign observation indices and disconnected support are rejected by the adapter; duplicate observation cameras are rejected by the typed checkpoint constructor. All cases are currently uncompiled/unexecuted.

## Proposed first gate (compile only; no implicit tests)

Use the exact authorized clean HEAD, record all source/tool/native/config pins before and after, a fresh external log/receipt directory, and source the same cloud-android-env.sh. Run the established Gradle8.13 binary with `--no-daemon --max-workers=1 --no-parallel -Dorg.gradle.jvmargs="-Xmx1g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8" --offline --dependency-verification strict -PcandidateSigning=test -PrealityForgeTestBuildType=debug -PrealityForgeInstrumentationQa=false -I /tmp/rf-relief-profile-pair-unit-draft/compile.init.gradle :app:compileDebugUnitTestKotlin`. Bound to600s with process-group termination as in accepted harness; record exitcode.

In a finally block run identical normal flags WITHOUT init script and `:app:compileDebugUnitTestKotlin`, independently bounded600s. Verify no VerifiedProfilePair*, SparsePipelineQualityCaptureHostTest*, SparsePrefixRunner*, SandboxNativeLoader*, OpaqueBitmapBridge* classes remain; clean unchangedHEAD/trackedfiles/native/APK hashes. Compile PASS requires exit0 AND restorationtrue. Unit execution needs a separately reviewed dedicated task; the full profile runtime is NOT authorized by compile approval.
