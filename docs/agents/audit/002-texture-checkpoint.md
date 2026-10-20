# Accepted surface replay: implementation and host evidence

This follow-up closes the native part of Agent 002's requirement that texture-only settings reuse accepted geometry. The implementation checkpoint is `d758de993a6b4d2f12dbc7bd30ea98b31c193784`. It is a local validation checkpoint, not a claim that the eventual integrated GitHub head passed Android or physical-device gates.

## Concrete gap and resulting behavior

The existing combined dense/surface/texture native adapter could reload depth maps, but it still fused samples and rebuilt the mesh when texture options changed. Stage identity property tests did not establish that this production computation was skipped. A full mesh, including the measured fields needed for visibility and atlas baking, was not persisted.

The native surface and combined texture producers now publish `surface-v1.rfmesh` after actual dense and surface acceptance. The binary retains all mesh positions, normals, colors, confidence/support, triangle indices and source-camera provenance, plus the actual dense samples and diagnostics. The texture-only entry point loads that accepted data and the matching depth maps, then invokes atlas baking and the texture writers. It makes no depth-search, fusion or meshing call.

Changing photographed atlas inputs or atlas resolution can produce different texture bytes while preserving the accepted geometry. A changed pose, intrinsic, rectified source pixel, depth identity, surface algorithm, checksum, count or depth-map content rejects the checkpoint. A rejected checkpoint is a cache miss; it is not accepted geometry. Cancellation and resource failures remain distinct typed failures. When atlas quality is unavailable, verified dense and mesh evidence remains present with `textureError`, without invented photographic success.

## Native integration contract

`NativeCore.textureAcceptedSurfaceCheckpointed` takes depth RGBA/dimensions, photographic RGBA/dimensions, the exact camera rows, the checkpoint file, new texture GLB/PNG partial paths, the cancellation token, and the exact depth directory/identity. The checkpoint filename is:

```text
.depth-checkpoints-m18/<64-lowercase-hex-depth-identity>/surface-v1.rfmesh
```

The outer native JSON contains these independently meaningful flags:

| Field | Meaning |
| --- | --- |
| `surfaceCheckpointAvailable` | A complete accepted checkpoint has been independently validated or published. This gates inclusion of the binary in a C8 SURFACE manifest. |
| `surfaceCheckpointWritten` | This invocation newly created and synced the accepted file. An existing identical checkpoint and texture-only replay report false. |
| `geometryReused` | The texture-only entry point loaded the complete accepted mesh. It reports true only on this path. |

The Kotlin adapter must propagate the first two fields into `densePreview` before recording its manifest. The native replay returns measured `dense` and `mesh` objects. It reports zero newly written depth maps and the number of actually loaded maps; prior computational workspace counters are not advertised as work performed by the replay.

Agent 009/002 selects the binary only after complete C8 dependency verification, full alignment validation, and exact comparison with the current measured sparse coordinate frame. The integration adapter copies the independently verified original mesh GLB/PLY into the new result's staging paths while preserving bytes and hashes. The native call writes only new photographic texture outputs. A settings hash alone is insufficient permission to reuse geometry.

## Bounded binary and file ownership

The `RFMSH001` format includes a version, bounded length/counts, exact rectified camera-and-pixel identity, surface algorithm/settings identity, complete depth-map SHA-256, and a trailing SHA-256 covering the preceding binary bytes. Decoding validates structure and geometry in addition to that checksum, including finite coordinates/normals, positive dimensions, count reconciliation, triangle indices, support and source-camera ranges, and settings.

The reader admits at most eight cameras, 50,000 dense points, 100,000 verified samples, 40,000 mesh vertices, 80,000 triangles and a 12 MiB file. One serialized buffer and the bounded decoded objects coexist during loading; the byte buffer is released before loading maps and baking textures. Compile-time object-size bounds keep decoded allocations below 10 MiB. The coordinated Agent 030 admission allowance is 24 MiB of additional peak working memory and 24 MiB of additional peak staging disk for the accepted checkpoint path, rather than an unmeasured runtime memory claim.

The path must be absolute, canonical and scoped to the exact SHA-named directory. Directory ancestors and final files are opened without following symlinks; only a regular singly linked file with the bounded size is accepted. Publication uses a uniquely owned exclusive partial, file sync, an atomic no-replace link, removal of that invocation's partial, and directory sync. Existing different bytes and unknown partials are preserved. A failed load leaves caller output unchanged. Texture outputs must be distinct new `.partial` files directly inside the owning private project directory; existing files and symlinks are rejected.

## Executed validation

The exact commands, exit statuses, environment and SHA-256 of each retained log are in [`reports/audit-002/provenance.json`](../../../reports/audit-002/provenance.json).

| Gate at `d758de9` | Actual result |
| --- | --- |
| GNU 13.3 CMake host build | Passed. |
| Full CTest | **13 suites passed, zero failed.** |
| Dedicated accepted-surface behavior executable | **10 cases passed.** The labeled synthetic random-color scene produced 924 measured vertices, 2,288 triangles and a 225,623-byte checkpoint. |
| Python regression suite | **123 passed, three failed.** Two failures were existing source-string assertions after Kotlin adapter moves; one was the final source manifest awaiting the integration owner's mechanical regeneration. The failed run is retained, not converted to a pass. |
| JNI C++ syntax with JDK 17 headers | Passed. This checks syntax and does not establish Android ABI execution. |
| Android Gradle/instrumentation at this child checkpoint | Not run. Two actual Android JNI tests were added for CI/device execution. |
| Real-object accuracy, memory/thermal measurements or physical device qualification | Not run. Synthetic host evidence does not establish these properties. |

The dedicated executable covers known SHA-256 vectors, bit-exact mesh/dense round-trip, production combined-solver publication, changed photographic colors and atlas resolution with unchanged geometry/depth bytes, changed optical geometry/settings/identity, truncated and oversized records, recomputed-checksum invalid topology, nonfinite geometry, replacement depth with a valid native checksum but changed SHA, symlink/path escape, existing/unknown file preservation, cancellation, and truthful missing-atlas output. See [`native-surface-checkpoint.log`](../../../reports/audit-002/native-surface-checkpoint.log) and [`native-ctest.log`](../../../reports/audit-002/native-ctest.log).

## Integrated acceptance still to record

The eventual integration head must run the actual Android compile and device tests, including the production C8 selector and Kotlin dispatch. A useful production regression is: finish a synthetic scan, retain its complete C8 closure and mesh/depth hashes, change only appearance settings, resume through `SparsePipeline.run`, and prove from the measured trace and unchanged files that alignment/depth/surface are reused and only texture is recomputed. Then change calibration and require the correct geometry descendants to miss. Native host tests here prove the new native behavior, while this full production-route/device gate remains separately identified.
