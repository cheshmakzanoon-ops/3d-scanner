# Minimal fifth-checkpoint capture source delta

The patch reproduces the reviewed COMPACT capture sources, not a production change. It appends bounded capture of the actual accepted native surface checkpoint after the existing assertions. All original core assertions and four artifact copies remain unchanged. The explicit normal Gradle flags are included in the wrapper delta.

## Reconstruct externally

1. Obtain the source commit in `source-pins.json` and copy only the five Kotlin files, `probe.init.gradle`, and `validate.py` from its `tools/relief_quality_validation` directory to a **new external** directory. Do not modify the repository or earlier evidence.
2. Verify each listed base SHA-256. From the new directory apply `patch -p1 < /path/to/fifth-checkpoint.patch`.
3. Verify both patched hashes and all unchanged-file hashes in `source-pins.json`.
4. Supply a separately reviewed machine-specific config binding JDK, Gradle, APP classes, native binaries and the full native-build provenance closure. The original invocation validated 447 provenance files. Those large machine-specific paths/files are deliberately not duplicated here. Do not infer that these source hashes alone establish native provenance.
5. The wrapper CLI remains `python validate.py --config CONFIG --config-sha256 SHA --expected-commit COMMIT --output FRESH_EXTERNAL_OUTPUT`. Execution requires the owner’s authorization; a copied config is not portable authority. Keep strict offline dependency verification, original native identities, one test, existing bounds, and normal-compilation restoration.

`capture-receipt-summaries.json` is a sanitized extraction, not the original receipt. It binds original receipt hashes, source commit, test outcome, restoration, captured files and their hashes. Original full receipts remain external and immutable.

COMPACT source hashes: main `70d4f474b7051d3fbfd588c4572cc102a028acfb260d6e0172401b2aa63011b7`, wrapper `d52b17f91bdeea3dd08cd6d7555d892d98618bfbe7256b9a116b1c757430195e`.

STANDARD is a separate preset-only capture, not this patch’s run. Its harness passed and restored; native topology checking was incomplete. Independent STANDARD coverage/error measurements were NOT_RUN when this summary was written. Neither capture establishes production resource admission, physical-device behavior, completeness, or quality PASS. JVM `-Xmx1g` is not a process-RSS limit.

The ancestor checks apply to a quiet owned synthetic fixture after its worker and native token have closed; they are not a claim of descriptor-anchored race safety on an arbitrary hostile filesystem.
