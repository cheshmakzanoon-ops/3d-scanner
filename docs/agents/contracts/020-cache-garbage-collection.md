# Agent 020 — derived artifact and cache garbage-collection contract

## Scope

This contract owns **derived-file inventory, retention planning, cleanup execution, cleanup journaling, and cache reuse integrity checks**. It does not own project deletion, photo deletion, Room schema, job persistence, publication, reconstruction orchestration, or storage UI.

The implementation is in `com.realityforge.scanner.data.cachegc` and exposes the assignment handoff types:

- `CacheArtifactManifest` and `CacheArtifactDescriptor`
- `CacheProtectionSnapshot` and `CacheCleanupExclusion`
- `CacheInventory` and `CacheInventoryBuilder`
- `CacheRetentionPolicy`, `CleanupPlan`, and `CacheRetentionPlanner`
- `CacheCollector` and `CleanupExecutionResult`
- `CacheReuseRequest` and `CacheReuseValidator`

## Non-negotiable safety invariants

1. **No filename guessing.** A regular file can become a deletion candidate only when an owner-provided manifest declares its exact canonical relative path and concrete category. Files absent from the manifest are inventoried as `UNKNOWN` and preserved.
2. **Originals are never cache cleanup targets.** `ORIGINAL` entries are always retained, even if a caller labels them stale. Explicit photo/project removal remains Agent 022's deletion-tombstone workflow.
3. **References win over retention policy.** Active-job, current-result, retained-result, active-publication, original-input, and user-pinned references always preserve their exact paths or declared prefixes.
4. **No symlink traversal.** Inventory, reuse validation, execution, and empty-directory pruning use no-follow attributes. Symbolic links and special files are reported and preserved.
5. **An incomplete inventory cannot delete.** Read failures, scan-depth/file-count truncation, or byte-accounting overflow convert otherwise deletable decisions into `KEEP_INVENTORY_INCOMPLETE`.
6. **Execution requires project exclusion.** `CacheCleanupExclusion` must be supplied by the repository/job owners. A reference-generation change or newly protected target stops execution with `REPLAN_REQUIRED`.
7. **Changed files are preserved.** Execution compares size, modification time, and file key when available. If the manifest supplies SHA-256, content is verified immediately before deletion. A changed path is never treated as the old planned artifact.
8. **Cleanup is resumable and idempotent.** A durable journal is persisted after every successful deletion. If a process dies after deletion but before journal persistence, the next run reconciles the missing path. Repeating an already completed plan causes no unrelated deletion.
9. **Journals stay outside the cleaned project root.** `FileCleanupJournalStore` rejects a journal directory nested beneath the target project.
10. **Integrity is not quality.** `CacheReuseValidator` verifies identity prerequisites, byte count, SHA-256, no-follow path safety, bounded reads, and a stable before/after file snapshot. A valid hash does not assert geometric or photographic quality.

## Manifest input from Agent 009

Agent 009 remains the authority for artifact identities and stage manifests. Its adapter must provide one bounded `CacheArtifactDescriptor` per owned file with:

- canonical relative path from the project root;
- category: `FEATURE_CACHE`, `DEPTH_CHECKPOINT`, `INTERMEDIATE`, `RESULT`, `TEMPORARY`, or `ORIGINAL`;
- lifecycle state: `CURRENT`, `REUSABLE`, `STALE`, `OBSOLETE`, `ABANDONED`, or `UNKNOWN`;
- stage/artifact identity and project revision when applicable;
- exact byte count and SHA-256 whenever available;
- `preserve=true` only for a deliberate durable retention decision.

Unknown manifest versions, duplicate paths, negative sizes/revisions, malformed hashes, traversal paths, and oversized manifests are rejected before filesystem traversal.

## Reference input from Agents 007 and 023

`CacheProtectionSnapshot` is immutable and generation-numbered. The generation must change whenever an active job/publication or retained/current result reference set changes. Exact and prefix protection maps carry typed reasons rather than display strings.

Agent 007 supplies active job/attempt references. Agent 023 supplies current result, explicitly retained previous result, and active publication/staging references. Agent 006 combines them while acquiring a project-scoped cleanup exclusion.

A cleanup exclusion must prevent a new conflicting project writer from publishing or acquiring a derived artifact reference until `close()`. The collector still reads the snapshot before each deletion; the repeated check is defense in depth, not a substitute for exclusion.

## Dry run

```kotlin
val inventory = CacheInventoryBuilder().build(
    projectRoot = projectRoot,
    manifest = artifactManifest,
    protections = protectionSnapshot,
)
val plan = CacheRetentionPlanner(policy).plan(inventory)
```

`CacheInventory` contains measured bytes per category, unknown/special entries, missing declared entries, reference reasons, and bounded issues. `CleanupPlan` contains every keep/delete decision, exact deletion targets, deterministic plan identity, and reclaimable bytes per category. Creating these values does not mutate storage.

Default retention preserves current/reusable work, one recent stale depth identity, and one recent obsolete result identity. Grace periods and bounded deletion limits are explicit policy values; callers must not silently reinterpret a plan.

## Execution and recovery

```kotlin
cleanupExclusion.use { exclusion ->
    val result = CacheCollector(
        FileCleanupJournalStore(projectOperationsRoot.resolve("cachegc"))
    ).execute(projectRoot, plan, exclusion, cancellation)
}
```

Execution outcomes:

- `NOTHING_TO_DO`: plan had no targets and stale journal state was cleared.
- `COMPLETED`: every target was deleted or reconciled as already missing.
- `CANCELLED`: cancellation was observed; completed progress remains journaled.
- `PARTIAL_FAILURE`: one or more I/O/journal operations failed; retry uses the same plan when references and fingerprints are still valid.
- `REPLAN_REQUIRED`: references, protection, path type, metadata, or content changed. Stop and build a fresh inventory/plan.

Actual deleted bytes are separate from planned reclaimable bytes. Consumers should refresh inventory after any run; a provider/filesystem may report allocation differently from logical file length.

## Cache reuse

Only `CURRENT` or `REUSABLE` derived descriptors with a non-null identity, exact size, and SHA-256 can become `CacheReuseRequest`s. The validator rejects missing, oversized, symlinked, changed, truncated, or hash-mismatched files with typed codes. Consumers must bind the validated artifact to the same project/revision/job read lease used for stage execution.

## Consumer handoff

Agent 021 may use `CacheInventory.bytesByCategory`, `CleanupPlan.reclaimableBytesByCategory`, and actual `CleanupExecutionResult.deletedBytesByCategory` in storage decisions. Planned reclaimable bytes are not guaranteed free space.

Agent 028 may render all `CleanupDecision`s and inventory issues for dry-run scope, then display typed execution progress/outcomes. UI must keep cache cleanup visually and semantically separate from project/original deletion.

Agent 010 should map typed issue/skip/failure codes into stable diagnostics and localized actions without exposing absolute private paths.

## Current integration state

The service and adversarial filesystem tests are complete in Agent 020's reserved paths. Production wiring remains an explicit integration step for owners 006/007/009/023: the current M19 code does not yet publish shared stage manifests, durable job references, or the required project cleanup exclusion. No fallback scans filenames to hide those missing contracts.
