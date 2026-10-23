# Exact NDK configuration prerequisite — no new runtime result

The first eight-view HOST attempt ([run 39997467140](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39997467140), source `c85ff88066e411f5f171c45f5c2f92077b3bc3f8`) failed before any production pipeline invocation: **pipelineAttempts=0**. Its primary owned-directory inventory race and separate offline restoration/configuration failure remain preserved in the [original evidence](experiments/eight-view-host-c85-failure-evidence/README.md). The inventory race was fixed independently; this prerequisite does not replace that evidence or claim a reconstruction result.

## Why exactly this package, and not CMake or NDK 27

The app pins NDK `28.2.13676358`. Pinned official AGP 8.11.1 source shows task creation constructs C++ configuration parameters and resolves that NDK even when the selected HOST task graph does not execute Android native tasks. `NdkLocator` uses the explicit DSL revision and the matching SDK side-by-side directory. Its final missing-NDK exception nevertheless prints the default version constant, explaining why the old restoration log named `27.0.12077973`; that string does **not** establish that the app pin was ignored.

CMake location resolution is deferred to native configure task execution or IDE native-model generation. This diagnostic requests neither, and its actual graph must continue rejecting all Android native tasks. No SDK CMake package is added. The system CMake used later for Linux host-native builds is a separate tool. Actual post-NDK Gradle configuration remains **NOT_RUN**.

Primary sources: [official AGP 8.11.1 sources](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.11.1/gradle-8.11.1-sources.jar), [AGP 8.11 defaults](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [explicit NDK configuration](https://developer.android.com/studio/projects/configure-agp-ndk). Exact source-entry hashes and call-path findings are retained in the [compact metadata package](experiments/eight-view-ndk-configuration-prerequisite/README.md). This is source-level diagnosis, not a recorded hosted Java call stack.

## Single direct-ZIP route

Only `ndk;28.2.13676358` is admitted. Its official [r28c Linux ZIP](https://dl.google.com/android/repository/android-ndk-r28c-linux.zip) is exactly **722,261,334 bytes**, with published SHA-1 `a7b54a5de87fecd125a17d54f73c446199e72a64`; [official package metadata](https://dl.google.com/android/repository/repository2-3.xml) and the [Android NDK release archive](https://github.com/android/ndk/wiki/Unsupported-Downloads#r28c) agree. The pinned version is historical, not a claim that it is the newest supported NDK.

Before downloading the archive, the controller reads at most 1 MiB of official metadata, verifies the exact package/revision/archive/checksum/license mapping, and requires license token `24333f8a63b6825ea9c5514f83c2829b004d1fee` already present as an exact line in the existing `android-sdk-license` file. The token computation is verified against official repository 31.11.1 `TrimStringAdapter` and `License` source. Existing license-file hashes are rechecked on success and failure. **No terms are accepted or written**, no SDK manager/license command is added, and Gradle automatic SDK download remains disabled.

A valid preinstalled exact package may be admitted through its bounded, non-aliased configuration-file identities, explicitly labeled `PREINSTALLED_METADATA_VERIFIED_NOT_ARCHIVE_PROOF`; it is never overwritten or removed. Otherwise, the existing non-aliased SDK `ndk` parent and an entirely absent revision directory are required. The archive is downloaded once, with exact byte/SHA-1 verification and a newly measured SHA-256 receipt. Its metadata-bounded central directory is SHA-256 checked **before** `ZipFile` allocates entry objects.

Extraction preserves all official entries and ordinary executable semantics, with no privileged mode bits. It rejects duplicate/traversing paths, non-directory parents, overlapping local data ranges, unsupported flags/types, size mismatches, external/dangling/cyclic symlink targets and aliases. All 35 legitimate package-contained symlinks are admitted from the verified archive and created last; none is silently skipped. The official ZIP's `source.properties` layout is directly supported by AGP's resolver; **no `package.xml` is invented**, no production DSL pin/path is changed, and no NDK executable is run by setup.

## Resource, ownership and failure bounds

- Metadata, archive acquisition and extraction share **600 seconds**, clipped by the existing controller work deadline. One request per object, no redirect/retry/fallback; socket waits are at most 15 seconds, so a blocking read may return after the cooperative phase boundary before it is rejected.
- Exact ZIP limits: **8,996 entries**, **2,294,132,951 expanded bytes**, largest observed entry 205,854,640 bytes (256 MiB per-entry ceiling), and 35 symlinks. The pre-pinned 1,540,425-byte central directory bounds parser allocation. ZIP expansion and archive bytes are checked while streaming, not merely afterward.
- Free-space admission accounts for archive and expanded package concurrently when they share a filesystem, plus the existing **2 GiB floor** and 256 MiB setup headroom. Separate filesystems get separate archive/package checks. This admits package setup, not a promise that the later whole build will fit.
- The fresh NDK directory is explicitly task-owned and included in the existing combined **8 GiB / 100,000-entry** live owned-tree accounting, using only exact contained-link aliases. The general scanner's symlink policy is not relaxed. These are periodic/cooperative bounds, not filesystem quotas or a guarantee against unrelated concurrent disk consumers.
- Successful extraction deletes its verified temporary ZIP. After ordinary restoration/source checks, the four configuration inputs are rehashed and only this run's newly created NDK inode is removed with no-follow, entry/depth/time-bounded cleanup. Preexisting packages are never removed. On failure the partial owned package is similarly removed; exhausted cleanup time, identity replacement, permission failure or incomplete evidence stays a terminal failure rather than being hidden.
- The exact actual task graph must report API 36, build tools 36.0.0 and NDK 28.2.13676358 and contain no Android native build tasks. No CMake installation, alternate NDK, retry or graph exemption follows a new failure.

The receipt separates official metadata inspection, actual downloaded archive hashes, extracted inventory summary, selected configuration-file hashes, license checks and owned cleanup. The complete archive and SDK tree are **not** evidence uploads. The original first failure remains primary when license/receipt/cleanup checks also fail.

## Validation and remaining limits

Actual local result: **222 guards passed in 12.52 s**; independent review is recorded separately, not inferred from this author result. Focused host controls use synthetic metadata and tiny inert ZIP fixtures. They exercise exact license admission before network, metadata changes, byte/deadline/space limits, central-directory-before-parser admission, path/duplicate/type/overlap/link rejection, real extraction/ordinary modes, first-error preservation and owned-only cleanup. A full fake-transport setup/finalize control checks one archive request, no SDK manager execution and no fabricated package metadata.

```sh
. /path/to/host-validation/activate.sh
RF_SOURCE_ROOT=/path/to/3d-scanner PYTHONDONTWRITEBYTECODE=1 \
  python -B -m pytest -p no:cacheprovider -q tests/test_eight_view_host.py \
  tests/test_eight_view_capability.py tests/test_eight_view_controller.py \
  tests/test_eight_view_ndk.py
```

No actual NDK ZIP was fully downloaded, extracted or executed during this implementation review. No new hosted workflow is armed by this change. Production pipeline, native capability and eight-view measurement remain **NOT_RUN** for this revised source. Original Android/device, sanitizer, full-suite, dense/surface and performance gates are unchanged; this prerequisite cannot qualify them.
