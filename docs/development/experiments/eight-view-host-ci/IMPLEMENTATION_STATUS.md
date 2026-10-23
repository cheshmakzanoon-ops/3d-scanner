# Eight-view HOST diagnostic — implementation checkpoint

**Not armed; no hosted/native/Gradle/pipeline attempt executed.** The separate workflow has
an unconditional false job admission, policy parent/message are `NOT_ARMED`, and the
successor package is pinned but not runtime-qualified. This document does not qualify an execution.
The approved design contract is `PLAN.md` (SHA256
`9a0e50575dcfa5412447f17525cfd8c37b2a4d97e3d6b477feb59646278cbe0b`).

## Implemented guard evidence

The isolated host suite exercises actual Python subprocesses (closed stdin, bounded output,
phase deadlines, late signals, inherited pipes and rapid double-fork/setsid), exact Git
ancestry/path checks, and synthetic capability/controller receipt failures. Small real GNU
compiler fixtures verify JNI declaration type conflicts and ELF dependency/RUNPATH checks.
These tests are **not** the actual OpenCV build, Gradle/KSP/Room execution or eight-view
measurement. See the latest `guard-test*.log`; provisional logs are not shipped as production
runtime evidence.

Process cleanup uses an ephemeral, verified per-controller Linux child-subreaper setting
and pidfds. Activation refuses preexisting children. Only kernel ancestry and bounded
`/proc` stat/PPID data are inspected; no unrelated process environment is read. The previous
subreaper setting is restored. Discovery errors do not prevent stopping independently known
owned processes, and any discovery/cleanup/evidence failure prevents PASS. This is process
lifecycle management, not a system permission/security/persistent-access change.

## Native provenance boundary

The reviewed builder (not a caller's arbitrary binaries) generates capability manifests.
The verifier requires the live run owner/work/source identity, exact native paths, mandatory
source/tool/header/archive inputs and authoritative in-memory command records matching
complete raw logs. It independently rechecks original OpenCV Java descriptors, JNI symbols,
ELF architecture, DT_NEEDED/RUNPATH, resolved provider hashes and actual smoke output.
The current compiled `NativeCore` descriptors generate a C declaration header that is
included while compiling the actual `jni.cpp`; same-named incompatible JNI parameter/return
types therefore fail compilation rather than passing on symbol names alone.

Before compilation, immutable source, admitted canonical system/JDK headers and configured
build inputs are snapshotted. Consumed `.d`/Ninja dependencies must match those hashes.
Only these four Java-generator headers may first appear during the build:
`modules/java_bindings_generator/gen/cpp/{core.inl.hpp,imgproc.inl.hpp,features2d.inl.hpp,opencv_jni.hpp}`.
Their scope is explicitly **POST_GENERATION_ONLY_NOT_PRECONSUMPTION**; no pre-consumption
hash is invented. All other unobserved compiler inputs fail closed. These generated names
follow the official [Java generator](https://github.com/opencv/opencv/blob/4.12.0/modules/java/generator/gen_java.py)
and [JNI build](https://github.com/opencv/opencv/blob/4.12.0/modules/java/jni/CMakeLists.txt).
The generated JAR is comparison evidence only; the actual app's pinned AAR classes remain
on the runtime classpath.

Robolectric 4.14.1 API35 loads `native/linux/x86_64/libandroid_runtime.so`, containing graphics
and SQLite support, rather than the older `librobolectric-nativeruntime.so` name. Its
[official loader](https://github.com/robolectric/robolectric/blob/robolectric-4.14.1/nativeruntime/src/main/java/org/robolectric/nativeruntime/DefaultNativeRuntimeLoader.java)
is corroborated by static `javap` inspection of the complete, checksum-pinned 53,484-byte
[nativeruntime 4.14.1 JAR](https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime/4.14.1/nativeruntime-4.14.1.jar)
(SHA256 `c07b66d315aec3272a7c64aa5f154b4194be2cc6030a733d16f2ee87330232a8`).
No code from that JAR was executed.

The API35 provider is in the pinned
[android-all 15 framework JAR](https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar),
expected whole-archive SHA256
`6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b`.
Bounded official ZIP64 central-directory reads found exactly one selected entry:
15,526,048 uncompressed bytes / 6,560,178 compressed bytes. The reviewed policy admits only
that entry and exact size, with a 32 MiB extraction ceiling. The separate repository-pinned
[nativeruntime-dist-compat 1.0.16 JAR](https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.16/nativeruntime-dist-compat-1.0.16.jar)
(SHA256 `2f4e879b00eed634d0e43353ecff80db4d5ce24b3b213d1e6053cb21b0ced10f`)
remains a required runtime dependency; its older provider is **not** an API35 fallback.
The large archives were not fully downloaded or executed during this metadata investigation;
controlled preparation must verify their whole hashes and the extracted entry bytes.

Metadata receipts: `metadata/android-all/robolectric-native-runtime-zip-metadata.json`
SHA256 `5342fd660582dd6d9d2fa2dc86b68fc5821e9e3fcc871c0ca245aeb7387d9a98`;
`metadata/robolectric-native-runtime-zip-metadata.json`
SHA256 `e8bfae8be70ec1d2362c7c7b3d5102805325c8d0ff757fc8682ef871e793618e`;
`metadata/actual-loader-identity.json`
SHA256 `f045aec1ab125025197343c792d144f01590758383ed34df9ec9d320b572d80f`.
These are metadata/static-loader evidence, not native compatibility receipts. The controller
hashes the exact framework and compatibility archives before and after execution. The
separately reviewed successor must bind actual own-JVM mapped paths/inodes/hashes after
Bitmap/Room use and after Room reopen. Archive hashes alone do not prove mapped providers. The selected JVM invocation
uses a failure-preserving finally postcheck for the capability, full prepared classpath and
observations, including timeout/cancellation paths. This read-only check uses remaining
cleanup time; an exhausted global deadline is explicitly incomplete, never PASS. The
original nonzero/timeout/cancellation remains primary if a postcheck also fails.

## Full-controller review corrections

The enriched preparation record is immutable: both actual Gradle runtime captures compare
all classpath entries **in order**, owner identities, original AAR and compiled-directory
hashes against its exact pinned hash before the test fork. They are retained separately as
`runtime-prepare.json` and `runtime-before-fork.json`; the controller also verifies both.
Added/removed/reordered providers are failures, not merely unchanged-old-file checks.

Classpath/data/artifact traversal uses bounded incremental inventories before materializing
or hashing contents, with per-file/cumulative byte/count checks and remaining deadline checks.
Ordinary classpath work cannot consume the reserved cleanup window; failure postchecks can.
Artifact sealing failure still attempts hash-framed **incomplete** essential terminal/original
failure evidence, withholding detected secrets. This is not an artifact or PASS substitute.
If the global deadline expires after an initial seal, a fixed-size emergency terminal
downgrade marks the existing receipt non-PASS/incomplete before reading the old manifest;
this does not scan the artifact tree or authorize more work. Unavailable IO is reported,
not a fabricated successful rewrite. Normal test-compilation restoration checks file facades/nested classes and the additional
`Api35ProviderContract` top-level object, which is not named after its Kotlin source file.
All native inspections, including scanner `nm`, retain the runtime environment; only explicit
compiler/build-tool calls use the repository compilation boundary.

## Remaining integration gates

- Independent scoped module review, then separate full controller/Gradle/workflow and Kotlin
  successor review. No approval is inferred from guard tests.
- The separately reviewed successor destination is
  `docs/development/experiments/eight-view-sparse-runtime-successor`, manifest SHA256
  `6d64c30f74751a6438af1c86687c35f8d8e1939d10611317735dbb9cf52fd8a5`.
  Compile exactly its nine runtime source files; the published source-only checkpoint is unchanged.
  The successor still needs actual Gradle compilation/native runtime admission, not merely this pin.
- Exact one-shot parent, message, changed-path list, implementation source manifest and clean
  source tree must be selected by the root after review; no recurring trigger is enabled.
- Actual hosted tool availability, SDK task graph, existing license admission, native build,
  API/ABI smoke and full runtime classpath remain unverified until the authorized single run.
- Bounded evidence-only artifact (32MiB/2000files/one day), plus complete hash-framed essential
  receipts. Runner hard-kill/storage failure can lose evidence; missing/incomplete evidence is
  not a measurement or PASS. A late external signal may legitimately determine terminal
  status even when the original failure receipt is retained.

The runtime cwd/tmp and observation staging are fresh owned directories. This is audited
input/dataflow isolation, **not OS confinement**: no poses, geometry, masks, scale, near/far or
render configuration are passed/read through the audited adapter, but no namespace/mount
or security boundary is claimed. All eight frozen160×120 PNGs and their supplied intrinsics
are used; no threshold tuning, view deletion, rerender, fake SIFT or retry is implemented.
`ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY` requires exact image/photo identity, all28 unique
READY pair completions, all8 camera IDs and the actual typed-BA outcome. A JUnit success alone
cannot turn incomplete/fallback results into all-eight success. This remains HOST sparse-prefix
research, not dense/surface accuracy, Android/device, performance or release qualification.
