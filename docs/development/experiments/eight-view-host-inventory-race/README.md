# Owned Gradle inventory deletion race — narrow HOST fix

The source-bound HOST attempt `c85ff88066e411f5f171c45f5c2f92077b3bc3f8` failed before any production pipeline invocation. Historical run39997467140/job120564631195/attempt1 evidence is preserved separately. No retry or workflow rearming is part of this fix.

## Reproduced mechanism

The old `tools/eight_view_host/runtime.py` inventory counted and queued a directory discovered by `scandir`. A later `scandir` of that directory raised `FileNotFoundError` when Gradle removed or renamed it meanwhile. Entry `stat` already tolerated a disappearing temporary file, but directory reopening did not.

`reproduce_queued_delete.py` uses a real background thread synchronized after root enumeration. On the unmodified c85 source it deterministically failed at inventory's `os.scandir(directory)` line31; `baseline-reproduction.log` retains that traceback. This establishes the local mechanism. The hosted receipt named a vanished Gradle transform directory but did not contain a full Python traceback; the exact hosted raising line remains an evidence-backed inference, not an observed stack.

## Scope and fail-closed behavior

Only the runtime live-inventory function and its host tests change. A retained root descriptor and recorded device/inode/type identities bind traversal. Each queued descendant component is reopened relative to that root with `O_DIRECTORY|O_NOFOLLOW`; its identity must still match. Only `ENOENT` for an already-discovered descendant is tolerated. Missing/replaced roots, replacement directories/files/symlinks, permission failures and other errors remain fatal. Descriptors are closed on success and failure.

Encountered entries still consume the count budget even when deleted; already observed file sizes are not subtracted. The existing byte/count/monotonic deadlines remain enforced, including while reopening ancestor components. This is a periodically checked live inventory, not an atomic filesystem snapshot or a hard instantaneous disk quota. No unrelated tree is followed or enumerated. The original owned-process cleanup and primary-error preservation remain unchanged.

Author:165 host guards PASS12.41s. Independent reviewer:165 PASS12.44s. Added controls include actual concurrent descendant deletion; directory/symlink/file replacement; root deletion/replacement; permission errors; deadline expiry; nested deletion; absent/aliased/non-directory roots; descriptor cleanup. Existing byte/count/alias controls remain. These are host controller tests, not actual Gradle/SDK/OpenCV/Robolectric reconstruction execution.

The portable reproducer now accepts `--source` and rejects any source hash except the original c85 runtime. From a repository containing that commit: `git show c85ff88066e411f5f171c45f5c2f92077b3bc3f8:tools/eight_view_host/runtime.py > /tmp/eight-original-runtime.py`, then `python -B reproduce_queued_delete.py --source /tmp/eight-original-runtime.py`. The retained historical traceback predates only this source-path argument/hash guard; the concurrent-delete mechanism is unchanged. The production source patch and exact test/runtime hashes are in `fix-receipt.json`; the accompanying source commit contains those two changed source files. Verify this evidence package with `sha256sum -c SHA256SUMS`.

## Separate restoration error: later official-source finding

The original offline restoration command reported missing NDK and preferred version27.0.12077973, while the app DSL pins28.2.13676358. Exact [official AGP8.11.1 source archive](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.11.1/gradle-8.11.1-sources.jar) inspection shows `NdkLocator.kt` lines91–92/312–321 prefer an explicit DSL version, line242 passes that resolved revision to installation, but lines252–255 always print the default-version constant in the final missing-NDK error. Therefore the printed27 value does **not** establish that the app's28 pin was ignored. `agp-source-receipt.json` records exact archive/source hashes; no JAR code or native tool was executed.

The [official AGP8.11 compatibility table](https://developer.android.com/build/releases/agp-8-11-0-release-notes) identifies27.0.12077973 as its default. [Official NDK configuration documentation](https://developer.android.com/studio/projects/configure-agp-ndk) describes the explicit `ndkVersion` setting. This source-level clarification does not retroactively change the immutable original failure receipt or prove a successful restoration.

NDK/CMake configuration prerequisites are assessed separately. This inventory fix installs nothing, changes no SDK licenses or security settings, does not change app/dependency pins, and does not waive any original qualification gate. Hosted corrected behavior and the actual eight-view pipeline remain NOT_RUN.
