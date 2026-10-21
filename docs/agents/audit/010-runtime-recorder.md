# Agent 010 runtime recorder follow-up

The independent review found a concrete production handoff missing: typed C9 progress existed in contract tests and import validation, while the running pipeline's detailed callback still carried display strings. This patch provides the actual recorder and report boundary for the engine/controller owner to call with measured facts.

## API and semantics

`RuntimeDiagnosticRecorder` is internal to the application module and takes one immutable `DiagnosticScope(jobId, attempt, sourceRevision)`. Its API is:

```kotlin
progress(stage: ScanStage, completed: Long, total: Long?, unit: ProgressUnit,
    reusedCheckpoint: Boolean = false): StageProgress
outcome(stage: ScanStage, outcome: StageOutcome, failure: ScanFailure? = null): DiagnosticEvent
recordFailure(stage: ScanStage, throwable: Throwable): ScanFailure
snapshot(): RuntimeDiagnosticSnapshot
close()
```

The execution owner creates a new recorder for each persisted attempt. It closes the old recorder so delayed callbacks cannot reactivate it. Progress is monotonic within a stage, keeps a fixed unit and an established total, and receives a globally ordered sequence within the attempt. Unknown totals remain null. A newly known total may appear once; it cannot then change. Failed validation leaves previously accepted history unchanged.

Progress does not imply publication success. Terminal outcomes are separately recorded only when the producer supplies that fact. Likewise, accepting an existing checkpoint does not invent counters for computational work that was skipped. The combined recent progress/outcome history holds at most 64 records, snapshots are immutable copies, and up to 64 actual failure reasons remain available even if old progress leaves the recent history. Access is synchronized for concurrent producers.

`ScanQualityReport.build(result, importedPhotos, runtime = recorder.snapshot())` checks the exact job, attempt and revision against the result. It preserves typed per-scene failure contexts before adding compatibility fallbacks, serializes measured `progressEvents` through the existing C9 progress serializer, and includes actual terminal events. It does not reconstruct those progress facts from message strings. Older schema-2 reports without the optional `progressEvents` field remain readable.

Report import/export validates bounds, sequence uniqueness/order, exact scope, integer counters, stable units/totals, boolean reuse flags and outcome/failure consistency. Unknown private fields are omitted from the normalized output. Allowlisted diagnostic text also rejects private filesystem paths. The addition does not introduce analytics or network transfer.

## Operational classifications

`OperationalFailureMapper` accepts known exception types and ignores their messages. It separates cancellation, memory exhaustion, resource pause, known insufficient storage, unknown storage measurement, revoked access, stale revisions, blocked project access, unsupported profiles, native library unavailability and other IO/native failures. Unknown available bytes stay absent rather than becoming zero. `DiagnosticOperationException(ScanFailure)` lets a source that knows the exact input/calibration/recovery failure preserve that typed stage, context and recovery action. Resource pauses have the explicit `PAUSED` outcome and `RESUME_JOB` action.

## Required production hooks

The Agent 001–010 owner supplies typed callbacks at the actual measurement sites: photos extracted, candidate pairs completed, camera-registration work, known sparse/bundle/dense stage boundaries, and actual exports written. Native substage callbacks whose only current information is display text remain display-only; they do not fabricate numeric C9 stage measurements. SURFACE/TEXTURE outcomes come from the actual typed native result. The final report is written after measured export completion so it can contain that event.

The root integration owner reads the typed live flow with the exact active job/attempt and presents the persisted typed outcomes. The normal capture/reconstruction and opt-in redacted diagnostic export routes remain owned by their existing adapters.

## Validation status at this child checkpoint

Sixteen focused JVM behavior tests were added in `RuntimeDiagnosticRecorderTest` and `RuntimeDiagnosticReportTest`. They exercise measured versus terminal semantics, immutable attempt scope, retry/late callbacks, invalid counter rollback, unknown totals, real concurrent producers, bounded retention, typed resource/storage/IO failures, unchanged per-scene context, actual report JSON round-trip, private-field redaction, and corrupted/reordered/cross-job imports. These are behavior tests over the real recorder and JSON codec, not source-string assertions.

**These new JVM tests and Android compilation have not yet run at this child checkpoint.** The integration owner runs the combined Gradle/Android gates on the final candidate. This document does not promote source review or the separate native host tests to a C9 execution pass. A production-route fixture must still verify that the engine/controller hooks feed the same typed stream, that its JSON survives restart/export, and that the UI rejects another job's update.

### Production compatibility identifier follow-up

The production factory combines independently versioned engine domains with `|`. The prior opaque-identifier grammar rejected that real value during report creation. Engine versions now have a separate, 160-character bounded grammar that accepts nonempty version domains joined by `|` and rejects paths, URI schemes, controls and empty domains. Job, artifact and settings identifiers retain their existing grammar. Two additional JVM behavior tests use `ProductionPipelineSnapshot.ENGINE_COMPATIBILITY` in an actual report round-trip and reject malformed version values; they are pending execution with the integrated JVM/Android candidate.
