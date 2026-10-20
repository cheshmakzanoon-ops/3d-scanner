from pathlib import Path
import json
base=Path('/workspace/agents')
cache=base/'050'
(cache/'docs/agents/contracts/050.md').write_text('''# Agent050 — durable bounded depth cache

`depth_checkpoint::Scope(directory, sha256Identity, byteBudget=32MiB)` binds optional intermediate caches to an exact64-lowerhex operation identity and a checked byte budget (maximum256MiB). Existing full-reference schema1 remains compatible. New tile schema1 (`RFTILE01`) compares global identity, reference/tile index, full dimensions, tile origin/dimensions, stride, solver version and bounded complete canonical solver/settings identity before decoding a bounded CRC-protected payload. Float depths/confidences and zero-depth invariants are validated; malformed, stale and partial records safely miss.

`TileKey`, `loadTile(key,map,&TileLoadStatus)` and `storeTile(key,map)` are additive. `TileLoadStatus` distinguishes Hit/Missing/Rejected/Unavailable.046 owns actual cached/recomputed/rejected/written counters and only assembles/stores a full reference after all tiles complete. Its solver identity includes actual resolved source adjacency and every effective quality/resource setting.009 supplies exact photo/pose/calibration/settings/engine identity; a caller-provided string is not independently proof of correct fingerprinting.

POSIX readers hold a shared directory-inode flock across complete validation/decode. Writers acquire a cancellable bounded exclusive lease, clean only exact owned orphan names, evict only recognized cache records, create a unique mkstemp, write/fsync(file), rename, and fsync(directory). RAII closes descriptors and removes failed temporaries. Eviction preserves active readers via the lease and preserves originals, unknown files and symlinks. Unsupported durable operations or failed storage return false while reconstruction can continue. No partial cache becomes a completed project result.

Host subprocess tests actually SIGKILL writers after temporary creation and after file fsync, resume prior completed reference/tile data and recompute interrupted work; independent processes test byte admission and actual-reader leases. A test-only syscall interposer returns ENOSPC from the real production write call and checks failure, cleanup, retained prior data and successful recomputation. This is injected storage exhaustion, not an actually filled physical volume. Test hooks/interposer are absent from production builds. Physical power-loss, Android lifecycle and storage guarantees remain separate NOT_RUN gates.
''')
(cache/'docs/agents/requests/050-to-004.md').write_text('''#050 to004 — native registrations

Register `depth_cache_manifest.cpp` and assertion-enabled `depth_cache_manifest_test.cpp` normally. `agent_050_process_recovery.cpp` requires its own executable built with `depth_checkpoint.cpp`, `cancellation.cpp`, `RF_CHECKPOINT_TEST_HOOKS`, and the normal include directory. It defines the test-only stage hook and Linux write-syscall ENOSPC interposer itself; do not link it as an ordinary production-library-only test. `tests/test_agent_050_native.py` contains the exact working compiler invocation. All checks use explicit require/throw and remain active under NDEBUG.

Reviewed implementation/test commit:4d7a2a7; production tile API source:ad1e835 and ancestors.046 normal-merges exact reviewed dependencies and tests the real execution path.
''')
(cache/'docs/agents/requests/050-to-009.md').write_text('''#050 to009 — exact identities and lifecycle

Use `Scope(directory, globalSha256,byteBudget)` for one immutable operation lease. Global identity must include exact admitted immutable pixels, full camera poses/calibration, resolved per-reference source adjacency, priors/coarse observations, effective solver/settings and engine. Refined profile is `cpu-refined-v2|local6|strict1|robust0`; changing profile must invalidate identity. The native tile key independently includes exact reference/tile/stride/solver/settings and actual source adjacency. Full-reference schema1 compatibility remains restricted by046 to its baseline-compatible path; refined work uses exact tile identities and never guesses truth.

Actual tile loads/stores are now wired in046. Native locks serialize readers/eviction/writers, but Android directory-retention/process-death policy and real scoped JNI identity are receiving-owner integration, not evidence supplied by this cache alone. Preserve originals/completed models and expose optional cache miss/failure without publishing partial project output.
''')
