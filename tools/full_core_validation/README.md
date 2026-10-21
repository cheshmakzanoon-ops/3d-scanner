# Opt-in full-core and publication HOST integration

Two separate opt-in entrypoints reuse actual current app/Room compilation and the previously reviewed bounded invocation/restoration protocol. No source is added permanently to app test directories; no default Gradle or CI gate changes. The packaged entrypoints themselves are NOT_RUN until an authorized owner records fresh runtime receipts.

## Scope

`core/validate.py`: three committed opaque synthetic relief JPEGs, real import/Room revision and leases, production COMPACT SparsePipeline normal return, global typed bundle adjustment/all3 cameras, dense/mesh/texture ready, production artifact/cache/manifest validation and database reopen. Current result must remain absent: publication belongs to a separate service/controller boundary.

`publication/validate.py`: the same full-core assertions plus real ProjectPublicationService staging, sealing and publication with live job lease;9 declared artifacts, verified identity/digests/result pointer/completed job and database reopen; legacy flat files fenced away while7 direct RepositoryExportSource options materialize exact bytes and validate. Appended-byte copies must fail integrity checks. Native derived export generation is exercised by availability, but dedicated derived-format materialization/validation is not asserted. No production controller is executed.

The Kotlin sources are frozen byte-for-byte from accepted external experiments. Historical core PASS1/1 took12.647s (904 sparse points,6851dense points,6741triangles); publication PASS1/1 took14.44s (6732triangles). These are separate observations, not deterministic mesh output guarantees. Earlier depth inventory and PNG stream parser failures were real producer/consumer defects corrected separately; first publication failure was an omitted experimental JNI entrypoint. Do not erase these failed attempts or describe packaging as a first-attempt runtime success.

Actual algorithm/native owners remain original, uninstrumented, acquired by one Robolectric sandbox. The sole explicit OpenCV platform substitute copies actual opaque ARGB_8888 Bitmap pixels into real RGBA Mat storage. There is no decoder/MIME fake, correspondence/pose/result injection, healthy-resource substitute or fake initLocal. Native graphics and SQLite are HOST infrastructure; no real Android permission/GPU/physical device/service/resource admission qualification. Three cameras register initially, with no claim of incremental-added-camera coverage. Synthetic assumed focal35=32.88262763223158 derives760pixels from800x600 and estimated center399.5,299.5; no measured physical calibration.

## Explicit configuration and owner invocation

Prepare a reviewed JSON configuration outside the repo, with **absolute paths** and observed SHA256 values:

```
{
 "schema":1,
 "repo":"<clean published repository>",
 "environmentScript":{"path":"<reviewed process-only Android/JDK setup script>","sha256":"<hash>"},
 "java":{"path":"<JDK17 bin/java>","sha256":"<hash>"},
 "gradle":{"path":"<existing Gradle8.13 executable>","sha256":"<hash>"},
 "opencvAar":{"path":"<current org.opencv4.12.0 AAR>","sha256":"<hash>"},
 "opencvClasses":{"path":"<exact AAR classes.jar runtime provider>","sha256":"<hash>"},
 "dropOpenCvRuntime":{"path":"<equivalent transformed duplicate runtime jar>","sha256":"<hash>"},
 "appClasses":{"path":"<actual current app runtime classes.jar>","sha256":"<hash>"},
 "native":{
  "opencv":{"path":"<verified Linux4.12 JNI>","sha256":"<hash>"},
  "shim":{"path":"<verified scanner JNI shim>","sha256":"<hash>"},
  "engine":{"path":"<verified scanner engine>","sha256":"<hash>"}
 },
 "provenance":[{"path":"<reviewed native build/source relationship receipt>","sha256":"<hash>"}]
}
```

Obtain paths from the owner's actual resolved test environment, never guessed cache versions. Bind all relevant native capability/source receipts in `provenance`; hashes alone do not establish that arbitrary rebuilt binaries match production source. The helper checks provenance bytes, not the truth of arbitrary receipt statements; owner review remains necessary. Tool executable hashes are not a full reproducible JDK/Gradle distribution attestation.

```
python tools/full_core_validation/publication/validate.py \
 --config "$CONFIG" --config-sha256 "$REVIEWED_CONFIG_SHA256" \
 --expected-commit "$PUBLISHED_COMMIT" --output "$NEW_EXTERNAL_PROOF_DIRECTORY"
```

Use Python without optimization. Existing Linux bash/readlink/readelf/nm, JDK17, Gradle8.13, locked Android dependencies, API35 offline Robolectric SDK and native artifacts are prerequisites. The helper downloads/installs/builds **no native dependencies** and uses Gradle offline/strict dependency verification. It **does** intentionally compile temporary test sources and then run normal `compileDebugUnitTestKotlin` restoration; only the designated Gradle owner should invoke it. Do not run concurrent Gradle/app-class mutations.

The explicit environment script is hash-bound and executed only in child shells; it must select the configured JDK and must not install tools or alter persistent settings. Inherited loader overrides and unreviewed JVM options fail closed; narrow existing proxy/trust-store options are allowed in the caller, then JVM override variables are cleared for Gradle. Test JVM also clears loader/JVM overrides. No emulator/service/credential changes.


## Exact identities and modes

Run `core/validate.py` for the independent no-publication contract; run `publication/validate.py` for publication. Use a fresh output directory for each. Native locations may vary, identities may not: each wrapper hardcodes the accepted OpenCV/engine/shim SHA256 set, matching its unchanged Kotlin prerequisite. Core uses the historical28-entry NativeCore shim; publication uses the additive29-entry production shim including NativeAssetExports.writeObserved. The original published AAR and classes.jar hashes are pinned too. The transformed duplicate is excluded only after every class entry matches the original AAR classes. Actual app native-owner class bytes must occur exactly once in the test runtime.

No binaries, fixtures duplicates, generated oracles, Gradle caches or credentials are bundled. `NATIVE_BUILD.md` documents the source-only additive capability recipe. Rebuilding on a different path/compiler can change bytes/RUNPATH; this does NOT authorize editing pins automatically. A changed capability requires independent source/build review and a new runtime receipt. Configuration provenance is byte-bound; the helper cannot itself prove the truth of arbitrary provenance statements.

## Bounds and fail-closed lifecycle

One worker/fresh test fork,1GiB heaps,2processor limit;150s operation,180s JUnit,5min task and600s process-group watchdog; restoration has a separate600s watchdog. Pathological native work may prevent structured cancellation from completing; outer watchdog is ultimate bound, and token/fixture resources are never released beneath a live worker. No peak-memory or performance guarantee is implied.

Clean exact commit, fixed three JPEG/calibration hashes, source/native/runtime/SDK and supplied provenance pre/post inventories are required. Exact one test, zero skips/errors/failures, compiled-input identity, successful normal compile restoration, no probe classes and clean unchanged source are all required for terminal PASS. Before restoration the status is PENDING_RESTORATION; failed/time-out/missing receipts never become success. Raw outputs contain local paths and should be sanitized before external sharing. Existing proof directories are never overwritten.

Python helper tests are mocked/static wiring contracts only: they do not execute Gradle, a JVM, JNI or reconstruction. A portable acceptance run is still required after review. Do not run concurrently with another Gradle owner or APK/class output mutation.
