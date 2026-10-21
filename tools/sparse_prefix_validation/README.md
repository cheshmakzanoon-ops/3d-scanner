# Opt-in two-photo production SparsePipeline prefix — HOST only

This separate gate temporarily compiles the five Kotlin sources here into the owner's **actual current app/Room test environment**, selects one test in one fresh fork, then restores normal test compilation. Nothing is added permanently to ordinary app test source directories. No production sources are modified.

## Actual scope and stopping point

Two fixed committed synthetic relief JPEGs go through actual AndroidImportInspector, production decode/quality JNI, import/journal/Room revisions, project/job leases, and unchanged `SparsePipeline.run(COMPACT)`. Actual features, matching and native geometry lead to an accepted SPARSE checkpoint. The caller then cancels its real Job; the worker must unwind before native token release. Production alignment validators reload the checkpoint before/after Room reopen, originals remain unchanged, persisted job is PAUSED at SPARSE, and no DENSE-or-later callback or current result publication is allowed.

This is **not completed reconstruction**. The successful original two-photo experiment observed524points and `verified_anchor_fallback`, with no bundle-adjustment result: final sparse execution requires at least three views. The test reports its actual measured outcome/failure field and never labels fallback as refinement success. Original failed fixture attempts and the successful proof remain separate; a portable rerun is required to establish this package's own outcome. A three-photo fixture is a separate future extension, not covered here.

No perfect correspondences, synthetic geometry result, pose injection, controller resource-health success or initialization bypass. The only explicit platform substitute is `OpaqueBitmapBridge`: Android Utils Bitmap conversion accepts opaque ARGB_8888 and copies pixels into actual OpenCV Mat. It does not validate AndroidBitmap native interoperability, transparent/RGB565 behavior, a physical device, GPU, controller/service admission, full scanner, metric scale, dense surfaces or physical quality.

Original native-owning OpenCV/NativeCore classes are uninstrumented **but remain acquired in the same sandbox**. There is no parent native preload, algorithm shadow or fake `OpenCVLoader.initLocal`. The original current AAR's OpenCVLoader/StaticHelper/Core/Mat class entries and actual NativeCore entry must each have exactly one matching runtime owner. The transformed duplicate OpenCV runtime jar is excluded only after byte-for-byte `.class` equivalence to the current AAR classes is checked.

Private output-directory aliases let original library names resolve: `libopencv_java4.so` points to verified OpenCV4.12 Linux JNI; `librealityforge.so` points to the verified scanner JNI shim. The shim's DT_NEEDED/RUNPATH must reach the separately verified engine. These are loading-name aliases, not version substitution. Original loader/token prerequisites verify actual mappings and native behavior inside the test. Aliases never enter a persistent library path or LD_LIBRARY_PATH.

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
python tools/sparse_prefix_validation/validate.py \
 --config "$CONFIG" --config-sha256 "$REVIEWED_CONFIG_SHA256" \
 --expected-commit "$PUBLISHED_COMMIT" --output "$NEW_EXTERNAL_PROOF_DIRECTORY"
```

Use Python without optimization. Existing Linux bash/readlink/readelf/nm, JDK17, Gradle8.13, locked Android dependencies, API35 offline Robolectric SDK and native artifacts are prerequisites. The helper downloads/installs/builds **no native dependencies** and uses Gradle offline/strict dependency verification. It **does** intentionally compile temporary test sources and then run normal `compileDebugUnitTestKotlin` restoration; only the designated Gradle owner should invoke it. Do not run concurrent Gradle/app-class mutations.

The explicit environment script is hash-bound and executed only in child shells; it must select the configured JDK and must not install tools or alter persistent settings. Inherited loader overrides and unreviewed JVM options fail closed; narrow existing proxy/trust-store options are allowed in the caller, then JVM override variables are cleared for Gradle. Test JVM also clears loader/JVM overrides. No emulator/service/credential changes.

## Integrity, resource limits and failure handling

Require clean exact commit. Hash all tracked source files, fixed JPEG/calibration inputs, scripts, providers, native artifacts, SDK and supplied provenance before/after. Exact offline SDK directory contains only pinned `android-all-15-robolectric-12650502.jar` (SHA6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b). Record actual compiled probes, complete actual test classpath/directory contents and unique native-owner class hashes; verify their bytes after testing before restoration.

One Gradle worker, no parallel tasks, two processors and1GiB Gradle/test heaps; one fresh test fork, test120seconds/task5minutes, outer process-group watchdog600seconds. Restoration has its own600second watchdog. Timeout sends TERM then KILL if necessary; no success is inferred from an interrupted or incomplete child. Worker join timeout does not prove native interruption: pathological child work can keep structured scope awaiting until the process watchdog terminates it. Resources are not released while the worker might still reference them.

A passing test first produces `PENDING_RESTORATION`, not final success. Only successful normal compilation restoration, absence of temporary probe classes, clean unchanged commit/source, exact one JUnit success and complete hash postchecks publish final PASS. Failures preserve logs and any available postchecks; restoring normal compilation is still attempted. No prior proof directory is overwritten.

Focused Python guards test mocked integrity/watchdog behavior and structural preservation of the restore chain, not execution of Gradle, JNI, Android graphics or the coordinator.
