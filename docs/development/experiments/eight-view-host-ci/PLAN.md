# Eight-view production SparsePipeline HOST diagnostic — review contract

Status: **PLAN ONLY / NOT BUILT / NOT RUN**. No workflow/root edits, downloads of binaries,
SDK actions, native builds or reconstruction executions performed for this assessment.
`dependency-assessment.json` records observed source pins and HTTP HEAD sizes, not fetched binaries.

## Decision and minimal capability

Use the existing actual app Gradle/KSP/Room graph in one standard public GitHub
`ubuntu-24.04` job, not an invented standalone Room replacement. Gradle8.13,
Kotlin2.1.21, KSP2.1.21-2.0.1, Room2.7.1, Robolectric4.14.1/API35 remain exact repo pins.
This is ordinary authorized CI build/test, not another coding environment or Android emulator.

The restored local JDK17/CMake3.31.6/Ninja/GCC14 and verified current scanner engine exist.
Local Android SDK/Gradle/Room/Robolectric/OpenCV Java JNI do not. Existing d4 sealed
Android artifact was inspected:162 entries/150903275 expanded bytes; no JAR/class,
generated Room implementation or runtime-classpath export. APK DEX is not usable JVM bytecode.

Official OpenCV4.12 release assets offer Android/iOS/Windows/docs, not Linux Java JNI.
Python cv2 is not a JNI substitute. Build official source commit
`49486f61fb25722cbcf586b7f4320921d46fb38e`, archive SHA256
`8f00b42869ab2836be36090f6631ae4c38ba59171e22a69a8e0f92f8ef1771d4`:
core,imgproc,features2d,java only, static components/fat Java JNI, original source unchanged.
Use the existing detailed OPENCV_BUILD.md switches: no IPP/OpenCL/CUDA/GAPI/codec/network
features, no tests/examples/apps/install. Fail-closed OPENCV_DOWNLOAD_PRE hook rejects every
OpenCV dependency request; FetchContent fully disconnected; verify enabled graph and inspect
any other enabled fetch mechanism. This is no-implicit-download policy, not a claimed OS
network namespace. Source/archive/license/manifests/configure/build logs and consumed headers
must be hashed before/after. Preserve failure logs; never loosen flags to rescue the attempt.

Use official pinned OpenCV AAR classes.jar, never generated Linux Java classes at runtime.
Compare native declarations/descriptors and JNI exports for Core, Mat, Algorithm,
MatOfDMatch, BFMatcher, DescriptorMatcher, SIFT, Feature2D, MatOfKeyPoint, plus actual
Imgproc APIs used by the Bitmap/grayscale path. Existing historical361+37 counts are context,
not sufficient unless current comparison really passes. Check native version4.12.0, ELF
x86_64/glibc closure with no missing symbols, actual Mat roundtrip/matcher/SIFT/Imgproc smoke,
and exact mapped library path. Generated JAR is comparison evidence only.

Build current scanner engine from exact source, then **jni.cpp-only** current shim per
NATIVE_BUILD.md; export/publication shim is unnecessary for this sparse prefix. Record compiler,
JDK headers/.d dependency hashes, linker/DT_NEEDED/RUNPATH and all NativeCore method descriptors
against JNI exports; calibrated initializer/typed BA/token lifecycle must be present.
No hardcoded historical binary hash is accepted as a new capability. Independent review approves
**in advance the exact source-bound build + capability-validator policy**, not arbitrary output
hash substitution. That frozen implementation binds official source/archive/AAR/dependency pins,
source-manifest-verified current TUs, explicit tool selection/version policy/commands and proof
schema. It must itself run/verify the build, consumed-source integrity, complete ABI/linkage and
real smoke gates before writing the sole admissible capability manifest. Caller-supplied binaries
or self-declared hashes cannot satisfy it. Runtime loader checks then consume that exact generated
manifest and recheck every hash/mapping. Replacing the deliberate UNQUALIFIED gate happens only in
a reviewed new adapter checkpoint implementing this policy; historical wrappers remain untouched.

## One-shot source/event boundary

Separate new diagnostic workflow; original workflows and software-emulator trigger unchanged.
Standard ubuntu-24.04 only; contents:read; no credentials persisted, no larger/self-hosted runners,
no KVM/root/sudo/security actions. Pin checkout action, ref=github.sha, fetch-depth=2.
Final parent SHA, exact opt-in message and narrow changed-path allowlist remain **PENDING**
until root publishes the frozen observation/adapter source. No trigger is armed by this plan.
Require public expected repository/default branch/main, nonforced/nondeleted/noncreated push,
attempt1, exact before/after/head agreement, one parent and expected ancestry, clean source tree,
exact source manifest and permitted changed paths. Enforce independently in YAML and controller.
The frozen implementation/policy files are covered by SOURCE_MANIFEST (which excludes itself);
source SHA comes independently from the admitted push. No file tries to embed its own final
commit or recursively self-hash. Expected parent/message and source bindings are separate.
Ordinary later forward pushes do not rearm; not a durable lock against deliberate owner resets.

Use preinstalled SDK/tooling and preaccepted license snapshot only. If official sdkmanager
needs a missing pinned package, bounded install with DEVNULL stdin and before/after license
hash equality; fail on new/unaccepted terms, absent license records or unresolved package.
Never use setup action defaults that accept licenses, `yes`, `--licenses` or new signing material.
Every Gradle invocation sets `-Pandroid.builder.sdkDownload=false`: AGP may not independently
install packages. Base app pins are platforms;android-36 and build-tools;36.0.0; NDK28.2.13676358
and SDK CMake3.22.1 are required only if the actual configured compile/test task graph needs them.
Inspect/record that graph before build; do not eagerly install unused NDK/emulator/platform-tools.
Only the explicit bounded sdkmanager phase may install an admitted missing package.
Gradle resolves only existing locked/strict-verified dependencies. Prepare the entire actual
runtime closure (including offline API35 framework and Robolectric native distribution) before
final offline execution; no online fallback after that boundary. No unreviewed dynamic versions,
dependencies or trust exceptions.

## Actual app/provider/input contract

Compile actual app/Room-generated outputs through current Gradle/KSP, with a temporary dedicated
source set/task outside ordinary test source directories; preserve all existing gates.
The app blanket-excludes HOST tests from every Test task except hostIntegrationTest. The new
narrow task must override **only its own** inherited exclusion, select the exact class/method,
fail on no match and emit actual test start/end events; ordinary tasks retain all exclusions.
Export actual resolved ordered task classpath, module coordinates/checksums and all class-output
hashes. Original current OpenCV/native-owner classes have exactly one owner in one Robolectric
sandbox, uninstrumented but acquired there. Exclude a transformed OpenCV duplicate only after
byte-for-byte class equivalence to the original AAR. No parent native preload/fake initLocal.
Run actual native graphics/SQLite. The sole explicit platform substitute remains the reviewed
opaque ARGB_8888 Bitmap-to-real-Mat bridge; no fake SIFT, matches, poses, DB or health result.

Adapter source checkpoint: `/workspace/shared/new-eight-view-sparse-adapter`, manifest SHA256
`62f8df0bc03ff4b713ae2fc01f0711860ada0b2a8a48d16dbc5c13d4939eda74`.
It is frozen/uncompiled and deliberately blocked pending the new capability review.
Test selector:
`com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure`.

Stage only the exact8 PNGs + observation-only manifest through reviewed inputs.py. No evaluation
poses, geometry, masks, visibility, scale, render-config or near/far passed as paths/properties/
resources/classpath; use truth-free runtime working/resource directories and inspect source for
all reads. This proves audited dataflow isolation, **not OS denial of a malicious JVM** accessing
other runner files/Git objects. No mount/namespace/permission changes are proposed.
Declared K=200px with unchanged estimated-pinhole production path; no invented measured profile.
No SIFT/matcher/PnP threshold retuning, altered images, dropped views, retries or warmup comparison.

## Bounds and process/evidence lifecycle (proposed maxima)

* Workflow75min, controller70min overall. Reserve the final120s for cleanup/postchecks/terminal
  evidence; launch no new work after the68min work deadline. All phase budgets clipped by that
  monotonic remaining work time. Cleanup has its own reserve and cannot legitimize incomplete work.
* Preparation/download10min; OpenCV configure/build30min; scanner/shim5min; app compile15min;
  adapter outer600s (operation90s/JUnit120s), cleanup/postchecks within global deadline.
  These are individual caps, not an additive guarantee; last phases may have less remaining time.
* OpenCV2 build workers; private owned GRADLE_USER_HOME, `--no-daemon --no-parallel --max-workers=1`,
  no persistent build cache. Kotlin compilation in-process (`-Pkotlin.compiler.execution.strategy=in-process`)
  with fallback disabled, not a shared daemon. Override repo parallel=true explicitly. Gradle build
  JVM retains explicit3GiB heap (repo setting),2 active CPUs; test1 fresh fork has2 CPUs/1GiB heap.
  Do not mislabel the build as1GiB. Record effective flags and peak observations where available;
  no unsupported hard total-RSS memory-limit claim. Track owned Gradle single-use daemon PIDs
  and starttimes/private daemon records as well as process groups; confirm none survive before PASS.
  Never global kill/stop unrelated daemons; unknown ownership blocks cleanup/PASS.
* Manual OpenCV source+AAR downloads: combined256MiB max, per-request300s, exact expected hash;
  safe bounded extraction/member/path/symlink validation. Maven/SDK phase separately time/output/
  owned-disk-growth bounded, not mislabeled as covered by manual download cap.
* Require4GiB free before native build, terminate owned processes if free space falls below2GiB;
  proposed owned dependency/build/output tree cap8GiB and100000files, polled during child work.
  Existing SDK writes, if required, additionally require free-space observation; do not pretend
  owned-directory cap bounds preinstalled global SDK size. No deletion of unrelated files/caches.
* Each command owns new process group, closed stdin, bounded captured output; TERM/KILL cleanup
  only owned groups/individually recorded owned PID+starttime identities. Preserve cancellation/watchdog through cleanup; missing/partial records
  cannot PASS. No retries. Preserve runtime sanitizer/loader environment; compiler-only
  native_compile_env boundary remains. Unexpected overrides may block, never silently sanitize.
* Raw log cap8MiB per phase; compact evidence total32MiB/2000files maximum. Any truncation/overflow
  is explicit, hash captured bytes and completeness; never hash redacted display as raw evidence.
  Prefix/redact public console output; do not upload environment, keys, SDK, jars, source trees,
  native libraries or executables. Bounded actual checkpoint .rfac evidence is data, not excluded
  as an executable binary; its original digest and reader validation remain necessary.
* Root approved bounded evidence-only Actions artifact32MiB/2000files/retention1day, plus strict
  hash-framed essential terminal/raw receipts in normal logs. No runtime bundle or billing/storage
  plan changes. Frame counts/order/byte length/digest/completeness must all verify after retrieval;
  root will promptly retrieve/verify. Upload success never implies test success. Preserve the
  first normal failure before cleanup; timeout/cancel produces explicit partial evidence and
  original classification. Unavoidable runner hard-kill or transport/log truncation can lose
  evidence: report INCOMPLETE/UNAVAILABLE, never invent retained baseline or PASS. Public runner
  compute does not imply unlimited free storage.

Exactly one adapter invocation. Require exact JUnit identity/start/completion, no skips/errors,
source/native/provider/input posthash equality and cleanup completion. Additionally require
all8 camera identities and typed BA, full pair ordinal1..28 accounting, accepted production
cache/alignment validators and DB reopen/no-publication. Successful JUnit alone is insufficient:
`ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY` is the only accepted measurement classification.
Incomplete/fallback/production exception preserves failure.json/pair-events and original outcome;
missing/timeout/cancelled is distinct. No dense/mesh/UI/performance/device/full-scanner claim.
Normal compile restoration and source postchecks must succeed; before that outcome stays pending.

## Review tests before execution

Adversarial event/ref/repo/parent/message/force/create/delete/attempt/head/path/merge tests;
wrong checksum/truncated download/archive escape/extra module/fetch attempt; absent/new license;
missing/duplicate/changed native owners, symbol/signature/version/provider mismatch; foreign/stale
source/native output; fixture truth/path/reorder/K/image mutations; missing/partial/duplicate JUnit,
incomplete all8/pair/BA classifications; no-output failure/timeout/output/disk breach/late cancel;
owned-group cleanup and source restoration failures. Test actual small ELF fixtures where helpful,
with native_compile_env only for compiler subprocesses. Unit mocks are not runtime qualification.

## Primary sources

* OpenCV official release/source: https://github.com/opencv/opencv/releases/tag/4.12.0
* Immutable archive: https://github.com/opencv/opencv/archive/49486f61fb25722cbcf586b7f4320921d46fb38e.tar.gz
* Build module/dependency options: https://docs.opencv.org/4.12.0/db/d05/tutorial_config_reference.html
* Java build source: https://github.com/opencv/opencv/blob/4.12.0/modules/java/CMakeLists.txt
* AAR: https://repo.maven.apache.org/maven2/org/opencv/opencv/4.12.0/opencv-4.12.0.aar
* Robolectric4.14.1 POM: https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.14.1/robolectric-4.14.1.pom
* API35 framework: https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar
* Native graphics/SQLite distribution: https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.16/nativeruntime-dist-compat-1.0.16.jar
* Robolectric offline configuration: https://robolectric.org/configuring/
* AGP automatic SDK downloading: https://developer.android.com/studio/intro/update#download-with-gradle
* Kotlin compiler execution strategy: https://kotlinlang.org/docs/gradle-compilation-and-caches.html
* Gradle single-use daemon behavior: https://docs.gradle.org/8.13/userguide/gradle_daemon.html

Official metadata HEAD confirmed AAR117745028B, framework184872954B, native-runtime157996126B.
Current repo verification metadata pins each SHA256. Official POM/LICENSE/NOTICE records are
preserved with fetched dependencies (OpenCV Apache2.0); no Android SDK terms are newly accepted.
These metadata observations establish feasibility/inputs, not successful runtime availability.
