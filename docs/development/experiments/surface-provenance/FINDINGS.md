# Fresh baseline checkpoint provenance — diagnostic PASS, production HOLD

The new unchanged-production capture records **6,137 strict crossings; all are between different native source-camera indexes, none within a source-camera grid**. This isolates cross-view overlapping surface ownership/fusion as the relevant failure category on this replicate, rather than within-grid folding. It does not identify which fusion gate caused low fusion or justify relaxing any gate.

| Native source-camera index pair | Crossings |
|---|---:|
| 0–1 | 2,030 |
| 0–2 | 2,424 |
| 1–2 | 1,683 |
| Within one camera | 0 |

Camera indexes are original checkpoint labels, not mapped to fixture photo names. Of the 6,137 crossing pairs, 5,644 share no vertex index and 493 share one index. 3,444 pairs contain no fused corner occurrences; 2,693 have at least one. Shared corners count once per participating face (twice if shared), not unique vertices.

The 5,746 retained vertices comprise 5,487 observed from one source camera, 240 from two, and 19 from three. They retain 6,024 observation references. Production accepted 6,865 observations and reports 278 fused observations. The surviving distinct-camera extra observations reconcile exactly as 240 +2×19=278. The 841 accepted observations absent from compacted mesh are consistent with removal of unused single-camera vertices; this is count accounting, not a quality judgment.

The mesh contains 6,734 total faces; 4,768 participate in crossings. Independent analyzer: 718,870 candidate pairs, 33,885 ambiguous pairs, no exhausted budget. All strict crossings are classified noncoplanar by the original diagnostic. Production recorded 4,873 boundary edges and 172 nonmanifold edges. Open geometry remains a known limitation.

## Evidence boundary

This is a distinct fresh baseline capture, NOT recovery of the older 6,176 paired-run artifact. The first earlier capture recorded 6,154; counts differ across captures. The cause of variation has not been established. Do not transfer provenance/pairs across these runs.

Checkpoint schema 2 checksum, actual depth identity, native baseline settings/algorithm digest, limits, finite values, topology indexes, observation/sample ownership and face-source observation guards all passed. Original native serialization matches captured mesh PLY byte-for-byte and complete meshPreview JSON semantically. The standalone production-header analyzer and provenance analyzer consume the same validated exported float coordinates; their exact ordered intersection-pair arrays match, not just totals. Seven negative cases passed: checksum, oversized count, face index, algorithm identity, depth identity, changed PLY, changed JSON.

Missing exact DenseView RGBA and reference depth maps mean this is **not native accepted-cache replay** and does not independently recompute geometryIdentity/mapIdentity. All stored sample normalSupportingViews are zero, but those fields are not the depth-neighborhood normals recomputed by production fusion. That histogram cannot explain the low fusion rate. Next useful measurement is original fusion neighborhood support and candidate rejection reasons under the exact same input geometry, without threshold/mesh changes; it requires a separately reviewed evidence-only capture/instrumentation scope.

## Immutable provenance

- Published repo: e8cc27a86d67e7962261f139d624e1ce809a296a; production baseline unchanged 09e87946.
- Capture: <FRESH_CAPTURE>
- Capture manifest SHA256: 0aeb12ec569a3b8195256ad04eda8ed1242c3c5e0704f16688558d5c8d8eb3f7
- Checkpoint SHA256: fd0efdc9190c254c7a36e591aaed95db59110ae36539b622b10ce5ac96c90dbc; 1,184,769 bytes.
- Depth identity: 61bcb704b31947582fc6a392a80a01bc205a8c167101e0cfddcf798c36fa4166
- Result receipt SHA256: 0f514f56945841c354ddd3f70ed55e7f91fdcddac53216890134fc840d738340
- Diagnostic source: <DIAGNOSTIC_SOURCE>; receipt source hashes include decoder e3fdd8ab…, runner eca7655…, standalone analyzer f7593f6….
- Terminal exit 0, 8.37seconds; 97 unique input pins unchanged including original native 4384f676… and JNI 427f2c8c…; single sequential compiler jobs, 2GiB per-process address-space cap, per-command limits as reviewed.
- The preceding run stopped fail-closed on an external decoder's dense medianConfidence width bug. Its failure evidence is preserved separately. Full native-schema audit established that field is double; reviewed v2 corrected only that read/helper type. No bound or geometry gate was weakened.
