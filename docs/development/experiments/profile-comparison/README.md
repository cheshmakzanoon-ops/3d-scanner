# Actual same-selection profile pair: HOLD current default

Measured source: `124deebbccb341cb04af9676a2385b79953d65ab`. Synthetic three-photo direct DensePreview experiment only; not full-controller, physical-device, cache or shipping qualification. Same accepted alignment, measured observation support, selector/source adjacency and COMPACT allocation preset; profiles change multiple algorithm/texture policies, so this is not single-factor attribution.

| Actual result | Compatibility | VerifiedRefinedCompact |
|---|---:|---:|
| Vertices / triangles | 5749 / 6738 | 3319 / 3669 |
| View01 truth-pixel coverage | 46.8165% | 29.7361% |
| Covered-pixel depth residual p95 | 0.0468573 | 0.0389517 |
| Native mesh intersections | 6162 | 2698 |
| Native topologyIncomplete | false | false |
| External self-intersection count | unknown: 5000-face budget | 2698 |
| Surface checkpoint | captured | absent by refined policy |

Residual populations differ; lower residual does not offset lost coverage or establish improvement. Both meshes remain open/defective/nonmanifold, not watertight. External `PASS_CHECK_EXECUTED` means the check ran, not zero intersections. Unlike the earlier STANDARD capture, these actual native counters have topologyIncomplete=false. Projected footprint is not 3D completeness; synthetic units are not physical units. **Keep default unchanged.**

## Failed gates remain failed
Compile-only wiring passed; 14 artificial adapter-only unit tests passed. Neither qualifies native quality. Three original full-cache attempts FAILED and restored normally: first compared database integer RUNNING against enum RUNNING; v2 failed mesh/texture/checkpoint conjunction; v3 retained checks and added diagnostics, proving refined surface checkpoint absent. Full-cache qualification was NOT subsequently passed.

A separate schema2 GEOMETRY_TEXTURE_CAPTURE_ONLY_NOT_REFINED_CACHE_QUALIFICATION run passed/restored. Existing native policy disables refined checkpoint compatibility/store; no checkpoint was fabricated. Its new scoped measurement completed in14.028s with unchanged inputs. Receipt summaries bind original full receipts/JUnit XML, not replace them. Raw full JSON/meshes remain external and immutable.

## Reconstruct sources externally; never patch production
1. Use committed `docs/development/experiments/surface-provenance/capture/README.md` and its fifth-checkpoint.patch to reconstruct COMPACT source from `tools/relief_quality_validation`. Verify committed recipe and compactBase hashes in capture/source-pins.json.
2. In a NEW external copy apply `patch -p1 < compact-to-geometry.patch`; verify geometryFinal hashes. This patch was freshly generated from actual frozen sources, NOT the stale exploratory exact.diff/prepare-main.py.
3. For failed history, copy reconstructed geometry externally, then apply geometry-to-failed-v3.patch, failed-v3-to-v2.patch, failed-v2-to-first.patch sequentially, checking each corresponding hash inventory. Never reinterpret those failures as success.
4. Capture wrapper invocation is `python validate.py --config CONFIG --config-sha256 SHA --expected-commit COMMIT --output FRESH_OUTPUT`. Requires separately reviewed machine-specific config/native provenance closure and authorization; no automatic run/install. Preserve offline verification, bounds, original identities and restoration.
5. measurement/ holds exact executed small adapter sources and original source pins. Follow its REVIEW.md but note historical NOT_EXECUTED labels were preparation-time; actual execution is bound here. Paths/config/runtime pins are environment-specific and must be explicitly reviewed/rebound on another host. Do not relax guards. Require exact successful schema2 receipt/manifest hashes from raw-evidence-bindings and receipt summaries. Never feed failed schema1 evidence to this successor.
6. adapter-unit/ retains source and compile/test scripts; large machine runtime pin files remain external, bound by source-hashes.json. Recreating them requires full review, not inventing hashes or claiming original runtime provenance.

Aggregate native scalars remain exact. Omitted arrays are explicit length/canonical-JSON-hash bindings; raw file hashes preserve exact-byte evidence. No binaries, images, checkpoints or mass geometry/track arrays are included. Archive contains no new reconstruction algorithm, production/default change, or quality PASS.
