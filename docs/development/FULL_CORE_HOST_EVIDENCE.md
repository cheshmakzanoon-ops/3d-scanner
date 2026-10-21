# Three-photo full-core HOST evidence — core and publication/export passed

The unchanged full-core test passed **1/1, zero failures/errors/skips, 12.647 seconds** at 2026-10-21T18:26:26.043Z on source `515f1435a497dd1734c51ef46f26e8f4ef136950`, tree `a91b282f3bce6f9e9b2c522aa652a24e50c4e6fd`. This is HOST service/algorithm integration, not Android device or full-controller qualification.

Actual unchanged COMPACT `SparsePipeline.run` returned normally with global typed bundle adjustment, all 3 cameras, 904 sparse points, 6,851 dense points and 6,741 triangles. Dense, mesh and texture statuses were ready; printed refinement/camera failure lists were empty. Initial registration included all 3 cameras; added camera count was zero, so this is **not incremental-added-camera evidence**.

## What passed

- Real Room imports, original-file/revision identity, reconstruction lease and durable stage callbacks; real native initialization/token ownership, OpenCV features/matching and scanner geometry/downstream algorithms.
- Exact returned artifact key and preview JSON validated without injected metadata. Production manifest verification covered FEATURES, MATCHES, ALIGNMENT, DENSE_DEPTH, SURFACE and TEXTURE.
- Real sparse/alignment and surface cache reload; production sparse/dense GLB, mesh GLB/PLY, sparse PLY, photographic GLB and atlas PNG validators. Dense PLY used a bounded test-only format reader because the existing sparse validator deliberately excludes dense representation.
- Embedded photographic PNG matched the standalone atlas bytes. Checks repeated after database reopen; durable job cursor TEXTURE and PAUSED state, untouched originals and **absent current project result publication** were asserted.
- Source/native/runtime inputs stayed hash-identical; no residual probe classes; clean source restoration succeeded. Receipt SHA256: `c58608c68c6afc82c0efc777d8b3a2cd63e6d15bdbdf7c7def407d06f983d4a0`.

## Red evidence retained, not rewritten as first-attempt success

1. On `75411af36dfc6259adda9d1b98b44cdd51a2b7c5`, the same frozen test failed at the real downstream depth-checkpoint inventory bound (9.988 s). This exposed a producer/checkpoint supported-bound mismatch.
2. After the inventory correction, on `0cb3583dfe146e340a5c4611f3eb019e4dd54105`, the same test reached ready stages but failed `PhotographicGlb` with `Invalid PNG compressed stream` (10.469 s). Separate native/JDK diagnosis identified valid header-only IDAT input yielding zero output while requesting more input; this was a parser defect, not a substituted Robolectric Inflater. The corrected production parser passed the unchanged full-core test above.

[evidence ledger](../../reports/cloud-full-core-integration.json) preserves exact failed/successful receipt and XML hashes, commits, test counts and restoration results. Original raw evidence remains separate; this sanitized report omits host names and absolute workspace paths. Generated reconstruction artifacts were fixture-cleaned, not archived by this report.

## Explicit platform and qualification boundary

Three committed synthetic opaque relief JPEGs and their calibration are hash-bound. Assumed synthetic focal35 is 32.88262763223158, giving fixture focal 760 pixels and estimated principal point (399.5, 299.5); no measured physical calibration profile is claimed. The only explicit OpenCV platform bridge converts actual opaque bitmap pixels to real RGBA Mat storage; it does not mock algorithms. Robolectric native graphics/SQLite and Linux native libraries are not Android decode/permissions/GPU/device qualification. No real controller scheduling, resource admission observer, foreground-service lifecycle or process-death durability is covered.

Operation bound 150 s, JUnit 180 s, task 5 min and external watchdog 600 s; native token/files are not released beneath live work. These are bounds, not performance guarantees. Full-core peak memory was not measured.

## Publication/export result and retained first failure

Publication candidate `7266368576dfb86e76afe83137af3c94f7098e0df9a345563192d00ebf8db5be` executed **1 test, 1 failure, zero errors/skips, 13.392 s** on the same source snapshot. Actual publication and both pre/post-reopen verified-result assertions completed before `RepositoryExportSource.available` called the missing `NativeAssetExports.writeObserved` JNI entrypoint. The pinned experimental shim exports only NativeCore entrypoints: this is an omitted HOST JNI capability, not demonstrated production publication failure. Source/runtime hashes and clean restoration passed. That first publication/export attempt was **FAILED**, not a gate pass. The separately pinned additive capability and same-sandbox original native owner were then reviewed and tested as v2 below. It adds actual ProjectPublicationService staging/sealing/publishing, real lease renewal, reopened verified identity/digests and RepositoryExportSource validation while fencing legacy flat-file fallback. It is service integration, not execution of the production controller itself.

A later portable harness should preserve the frozen tests and hash-bound fixture/native/class identities, accept explicit verified external native inputs, retain fresh-fork sandbox ownership, strict offline resolution, bounded execution and restoration, and emit sanitized receipts. It must not bundle native binaries, depend on private /tmp paths, weaken gates or silently count optional integration as default qualification. No portable package is implemented here.


### Corrected HOST capability: actual publication v2 PASS

The same main test source, unchanged `7266368576dfb86e76afe83137af3c94f7098e0df9a345563192d00ebf8db5be`, passed **1/1, zero failures/errors/skips, 14.44 seconds** at 2026-10-21T18:37:51.443Z on source `515f1435a497dd1734c51ef46f26e8f4ef136950`. The original first-attempt failure above remains retained. V2 adds the missing production export JNI capability and preserves original NativeAssetExports class bytes/uninstrumented native method in the same owning sandbox; no export assertion was removed or weakened.

Actual ProjectPublicationService staged/sealed/published **all 9 declared artifacts**, with exact identity, size and SHA checks, completed job state and project result pointer. Database reopen returned the verified current result. Legacy flat artifacts were fenced away before RepositoryExportSource advertised, materialized and validated **7 direct exports**: preview JSON, sparse GLB/PLY, mesh GLB/PLY, textured GLB and atlas PNG. Materialized bytes matched published artifacts. Appended-byte copies were rejected without modifying publication (length/integrity negative, not deep grammar corruption). Dense artifacts are published but not direct source options; derived serializers were invoked during availability, without claiming dedicated materialize/validation coverage for their derived formats.

This second core execution observed 3 cameras, 904 sparse points, 6,851 dense points and **6,732 triangles**. The earlier standalone core PASS observed **6,741 triangles**; these are separate run observations, not byte-deterministic mesh claims. Global bundle adjustment and all ready-stage validators passed in both. The publication test additionally reran original cache/artifact checks after restoring fenced files.

Publication receipt SHA256: `6f08268964bd1fb4b43fea7c214a983d626b0c6113b1b093158eefcce2d5f9ba`; XML SHA256: `01c5a6f0eb2c62b753db2a43c9976bd12bf5a0dbb787668d3e33c71bcbcdd9fc`. Additive JNI SHA256: `519a3d15329520211d1d67756fe619308453e35445b349f2c162362b941af9ea`. Native/source/compiled inputs remained unchanged and clean restoration succeeded. This qualifies the actual publication service boundary on HOST, **not execution of the production controller or physical-device qualification**.
