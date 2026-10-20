# Independent review of Agent 009/010 production integration

Reviewer: the Agent 011–020 audit worker. This review inspected the exact Agent 002/009/010 requirements in `PRODUCTION_100_AGENT_EXECUTION_PLAN.md`, the contracts/tests and production sources through the Agent 001–010 checkpoint `570874c`, and the native follow-up `d758de9`. It records source findings and executed native evidence separately. These checkpoint IDs do not claim validation of the final published integration head.

## Agent 009: identities and accepted geometry

The canonical input/manifest implementation separates reusable content from project/revision/job/attempt ownership. The identity tests independently vary image order/content, calibration, coordinate convention, engine compatibility, and each stage's algorithm and settings. They assert precise descendant invalidation, including texture/export and LOD/export separation. The tests also verify explicit ownership rebinding rather than weakening content verification.

`PipelineArtifactSession` is an actual production producer: it syncs the input snapshot, verifies file byte sizes and hashes, records a C8 stage manifest and only then records the accepted durable cursor. The production feature and matching caches now retain and verify actual measured data. `MeasuredAlignmentCheckpoint` records complete cameras, observations, tracks and sparse geometry; the independent validator reprojects observations and checks positive depth. The alignment corruption tests include values with a recomputed valid checksum that still describe invalid projection/geometry. This addresses the requirement that a checksum alone must not validate geometry.

`PipelineSurfaceCache` checks a complete C8 SURFACE dependency closure, exact stage identities, the full accepted alignment, and equality with the current measured sparse frame before exposing `VerifiedSurfaceCheckpoint`. It validates the diagnostic revision/artifact, exact depth directory, required depth-map refs, binary mesh ref and source GLB/PLY refs. A complete unchanged texture is separately eligible only through its verified TEXTURE dependency closure. These checks are substantive production integration beyond the earlier standalone identity classes.

### Literal Agent 002 reuse gap found and repaired

The prior dense adapter reused depth but recomputed fusion/mesh on a texture-only change. This violated Agent 002 instruction 3 even though stage hash tests passed. Follow-up `d758de9` adds a complete bounded accepted-mesh binary and an actual JNI/C ABI texture-only entry point. Ten real host cases exercise save/load/retexture and corrupt identities; all thirteen host CTest suites pass. The recorded format, safety bounds, commands and limitations are in [`002-texture-checkpoint.md`](002-texture-checkpoint.md).

The integration owner is responsible for the Kotlin selection/dispatch and byte-preserving mesh export staging. The artifact producer must use `surfaceCheckpointAvailable`, not infer availability from file existence or confuse it with the separate `surfaceCheckpointWritten` counter. The new full-route Android regression and restart tests still require execution on the published integration head. Identity property tests are not a substitute for that dispatch test.

## Agent 010: concrete production disconnect found

At the reviewed Agent 001–010 checkpoint, `StageProgressLedger` was used by contract tests and diagnostic import validation, but not by the running pipeline. `ScanQualityReport.progressToJson` had no runtime producer. `ReconstructionExecutionController` had newly connected durable C8 stage acceptance to 007 `JobProgress` values, but the detailed live stream still carried `SparsePipeline.Progress` string stages. `ScanQualityReport.build` reconstructed terminal failure events instead of retaining the measured typed stage stream.

The contract itself covers fixed `(job, attempt, stage)` monotonicity, unknown totals, stable units, distinct sparse fallback/missing texture/unknown scale/unobserved surface conditions, bounded retention, and allowlisted context. Those tests do not prove that the production callback path supplies the same data. The literal handoff requires the owners to report raw facts through C9.

The integration team has accepted this finding for an additive `RuntimeDiagnosticRecorder`, actual typed photo/pair/stage hooks, report serialization and focused behavior tests. The recorder must have an immutable exact attempt scope, bounded immutable snapshots, strictly scoped counters and measured reuse information. Progress must remain separate from terminal success; no global percentage or success event should be synthesized from a callback label. Unknown dense substage counters should remain unknown unless the native producer measures them.

Remaining acceptance evidence after that patch is the actual Android compile plus a production-route fixture showing typed progress/report/UI identity, retry reset, stale update rejection, failure preservation and opt-in redacted diagnostic export. This review does not mark those device gates passed. The 011–020 auditor supplies recorder/report code; the 001–010 owner connects the engine/controller; the root integration owner presents the typed information and records final-head CI results.

## Review limits

This was an independent acceptance/source review, not a rerun of the JVM or Android suites at `570874c`. Full native validation at `d758de9` is retained with its exact failures and scope. Physical-object reconstruction accuracy, 30/100/250-photo qualification, real camera session behavior and device resource measurements remain separate gates and must not be inferred from these deterministic fixtures.
