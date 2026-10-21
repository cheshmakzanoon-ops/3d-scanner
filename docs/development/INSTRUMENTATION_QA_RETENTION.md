# Test-signed release instrumentation ABI gate

This is an opt-in **QA-retained** release build, not proof that the default
production-optimized APK works. R8/resource shrinking remain enabled. White-box
instrumentation APKs reference shared target dependencies that ordinary application
optimization may legitimately remove or transform. The observed runner failures
were `androidx.tracing.Trace`, then `kotlin.LazyKt`, before any tests executed.

The finite surface is derived with the same R8 8.11.18 bundled in AGP 8.11.1 using
[the official TraceReferences CLI](https://r8.googlesource.com/r8/+/refs/heads/main/src/main/java/com/android/tools/r8/tracereferences/TraceReferencesCommandParser.java).
No all-application/package wildcard keep and no minification disable is used.
Espresso3.6.1 matches the reviewed AndroidX runner1.6.2/core1.6.1 graph; the previous
3.5.0 screenshot adapter had a genuine incompatible ViewCapture method reference.

## Ordered workflow

Only one Gradle owner may run these steps. Use the pinned offline toolchain and
existing candidate signing setup. No credentials, permissions, application
runtime dependencies or network behavior are added by this workflow.

1. With explicit QA retention enabled, prepare normalized pre-shrink bytecode
   without invoking assembly or R8:

   ```sh
   ./gradlew --offline -PcandidateSigning=test -PrealityForgeTestBuildType=release \
     -PrealityForgeInstrumentationQa=true \
     -I tools/export_instrumentation_classpaths.init.gradle \
     :app:exportInstrumentationQaClasspaths
   ```

   The task depends on release app/test Java compilation (and their Kotlin/resource
   producers), fingerprints source/config before task execution and after compilation,
   and rejects active edits. It also depends on the QA AndroidTest-only metadata
   normalization transform. Only its downstream normalized artifact is exported as
   test input; raw input hashes remain bound in the receipt. It writes `app/build/generated/instrumentation-qa/classpaths.json`.
2. Run `python3 tools/instrumentation_abi.py generate --help` for the required explicit
   inputs: repository root; exported classpaths; installed JDK17 `bin/java`; AGP's
   exact `builder-8.11.1.jar`; verified `android-all-15.jar`; SDK `android.jar`, optional
   test base/runner jars, and actual JetBrains23.0.0/error-prone2.26.1 annotation jars.
   Pass `--accepted-diagnostics tools/instrumentation_qa_generation_exclusions.json`.
   Output must be `app/build/generated/instrumentation-qa`. Do not hand-edit or bless
   an export fingerprint. Generation verifies tool version and input content hashes,
   rejects conflicting shared classes and symlink inputs, and writes finite rules
   plus a manifest only after the trace succeeds with exactly reviewed exclusions.
3. Assemble app+test release with `-PcandidateSigning=test`,
   `-PrealityForgeTestBuildType=release`, and `-PrealityForgeInstrumentationQa=true`.
   Other signing/variant combinations and missing/stale inputs fail closed.
   File-existence and hash verification run after pre-shrink compilation/normalization
   and before both app/test R8 and packaging. Preparation can therefore run before
   rules exist, but assembly cannot bypass missing/stale receipts.
4. Freeze named copies, e.g. `app-release-qa-retained.apk` and
   `app-release-qa-retained-androidTest.apk`. Run checker `check --help` with those
   exact paths, root, generated manifest, same Java/R8 and a separate output receipt.
   Only manifest-pinned runtime libraries may resolve references. New diagnostics
   fail; never reuse a success receipt after failure. Exact final diagnostic context
   changes require explicit review, not an automatic ignore list.
5. Only after final pair linkage passes, install those exact two APK hashes and run
   actual instrumentation. Static linkage is not device-safety proof. Record tests
   executed/results and the QA retention surface separately from signing/manifest
   audit. Default shipping APK qualification, physical-object quality, complete
   sessions and production signing remain separate gates.

## Framework and documented exclusions

The audit-only full framework source is Maven Central
`org.robolectric:android-all:15-robolectric-12650502`; publisher SHA512 was verified.
Its SHA256 is `6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b`.
It is never an application dependency or shipped library. The checker library
contains precisely the installed public SDK class set, replacing only
`android/app/Instrumentation.class` with the actual API35 declaration. No aggregated
android-all third-party classes can hide missing app/test dependencies. This hybrid
library is not proof that newer public APIs exist on API35. JDK StringConcatFactory
is used only for pre-desugar generation; it is forbidden as a final runtime library.

The exact generation list records optional desktop Hamcrest JavaBeans and compiler
annotation references, plus three legacy MonitoringInstrumentation dispatch wrappers.
The three platform overload exceptions are pinned to monitor1.7.2 bytecode SHA256
`3641a1bd19fade3c43351d657bd5845f51f43ee2e2a40eaaee5d124d7af374b1` and the API35
framework hash. Target owner, full return/parameter signature and source method
context must match; other Kotlin/app/AndroidX missing APIs remain hard failures.
Modern Activity+Bundle and String+Bundle platform dispatch overloads resolve.
Receipts say `PASS_WITH_DOCUMENTED_EXCLUSIONS`, list each diagnostic/rationale,
include `qaRetention=true` and `production_equivalence=false`, and bind exact APK,
source, rules, manifest, checker, shrinker and runtime library hashes. They never
claim zero missing references or successful device execution.

AGP test R8 also needs a narrowly scoped diagnostic rule for the desktop enum
`javax.lang.model.element.Modifier`. Only the opt-in QA branch adds
`gradle/instrumentation-qa-test.pro` through `testProguardFiles`; the application
and normal production build receive no such suppression. The sole diagnostic suppression names that
exact class. Original graph inspection found references only in error-prone2.26.1
`IncompatibleModifiers` and `RequiredModifiers`: both are CLASS-retained
ANNOTATION_TYPE interfaces with deprecated abstract `value()` signatures and no
method bodies. The independent checker accepts only the exact summarized annotation
diagnostic, not ordinary invocations or other source contexts. This exception is
recorded in the receipt, not presented as a zero-missing runtime graph.

The legacy exception's literal target owner is AndroidX
`androidx.test.internal.runner.hidden.ExposedInstrumentationApi`, **not** directly
`android.app.Instrumentation`. In the pinned monitor bytecode, that abstract bridge
extends `android.app.Instrumentation` and contains only a constructor; it declares
no dispatch implementation. `MonitoringInstrumentation` is its subclass and the
five `invokespecial` dispatch references resolve through that inheritance chain.
Three legacy signatures are absent on API35; the two modern signatures resolve.
Inspection of the complete original source/target class sets found the hidden-owner
plus `execStartActivity` reference only in `MonitoringInstrumentation`, not ordinary
application/test classes. Thus these exact three pinned inherited-framework calls
are the only permitted AndroidX-target exception; **every other** missing AndroidX
(and all Kotlin/application) target remains a hard failure.

The first frozen QA pair's final linkage check caught two additional AndroidX
metadata references, and was rejected without a success receipt. Test R8 had
renamed the private `lambda$static$0()` methods in `TestOutputEmitter` and
`Checks$ThreadCheckerSingleton`, while retained anonymous-class `EnclosingMethod`
annotations still referenced their original names. DEX disassembly found the two
old names only in those system annotations, not invoke instructions. An exact-method
keep-rule attempt produced unchanged APKs in the actual v3 rebuild: mandatory
lambda desugaring still left the metadata references unresolved. Those ineffective
keeps were removed. No AndroidX diagnostic exception was added. The replacement
normalization below has passed an isolated compiler/reference replay; full APK
and device outcomes remain in the cloud ledger, not implied by that replay.

Historical pre-normalization generated surface (not device qualification): 1,067 type blocks:
785 classes, 160 interfaces, 79 enums and 43 annotation interfaces; 4,583 lines,
309,037 bytes. The schema1 manifest's `retained_classes=1024` counter excluded
`@interface` blocks. Schema2 corrects this counter to include annotation types;
fresh generation must determine current counts and hashes. Rules SHA256:
`80f7d07f7a124610274f7081c478fee05e2c88415a8fa7626c0043b8f9a412e1`.

## Exact QA-only initializer metadata normalization

The public AGP8.11.1 [ScopedArtifact transform API](https://developer.android.com/reference/tools/gradle-api/8.11/com/android/build/api/variant/ScopedArtifactsOperation)
is registered only on the release **AndroidTest** component under the explicit QA
flag. No target-application artifact is transformed. It applies the classfile
initializer correction described by [JDK-8348584](https://bugs.openjdk.org/browse/JDK-8348584):
retain the enclosing class, set the `EnclosingMethod.method_index` field to zero.
This is a local, narrowly pinned QA workaround, not an official compiler patch.

Only these original monitor1.7.2 class bytes are accepted:

| Class | Original SHA256 | Two-byte field offset |
|---|---|---:|
| `TestOutputEmitter$1` | `cadaf2750969b978d060109bc20932b545a91dd54d2a3f7c533ea23d71a0ce45` | 1243 |
| `Checks$ThreadCheckerSingleton$1` | `4730062f582788f36b20ace3cfdf5e9bdaefe2ff4fb0ad0d6d200bb526d96f09` | 1392 |

Each field changes `000c` to `0000`; all other bytes, instructions and InnerClasses
metadata remain identical. Owner/name/descriptor, class identity and hash must
match; malformed, changed or already-normalized inputs fail. Deterministic merge
rejects conflicting classes. Its only excluded classfile path is the Java9 JPMS
multi-release descriptor `META-INF/versions/9/module-info.class`, which has no
Android executable-class role; every source/path/hash is recorded and checked.
All other META-INF class entries fail closed. Embedded regression fixtures carry
[AndroidX attribution](../../tests/fixtures/androidx-monitor/NOTICE.txt).

The schema2 generation and final receipts include the normalization receipt,
all original input/helper/output hashes, exact per-class deltas and descriptor
exclusions. Verification reconstructs the entire normalized jar, then checks the
original test-only class set and unchanged bytes against the actual downstream
artifact. Both app and test shrink/package paths depend on this verification.
An isolated pinned-R8 release replay of the four relevant original classes failed
with the two metadata references; the same replay after normalization passed,
with DEX EnclosingClass rather than stale EnclosingMethod annotations. That is
compiler/reference evidence only, not a completed installed-app workflow.
