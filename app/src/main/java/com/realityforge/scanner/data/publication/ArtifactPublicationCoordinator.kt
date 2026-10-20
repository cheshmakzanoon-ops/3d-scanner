package com.realityforge.scanner.data.publication

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Journaled, revision-bound publication coordinator.
 *
 * Producers write only into an operation-owned staging directory. A complete set
 * is validated, promoted by one same-filesystem atomic directory move, validated
 * again, and only then offered to the metadata transaction port. Existing current
 * and previous completed pointers are never cleared by this class.
 */
class ArtifactPublicationCoordinator(
    rootDirectory: File,
    private val metadata: PublicationMetadataPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val faultInjector: PublicationFaultInjector = PublicationFaultInjector.NONE,
) {
    private val storage = PublicationStorage(rootDirectory)

    fun begin(plan: PublicationPlan): ArtifactPublicationSession {
        require(plan.declarations.none { it.relativePath == RESERVED_MANIFEST_PATH }) {
            "An artifact declaration uses the reserved manifest path."
        }
        val snapshot = requireSnapshot(plan.identity)
        validateSnapshot(snapshot, plan.identity, snapshot.currentResult?.setId, snapshot.metadataVersion)

        val journalPath = storage.journalFile(plan.identity)
        require(!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            "A publication journal already exists for this operation attempt; reconcile it before reuse."
        }
        val stageRelative = storage.stageRelativeDirectory(plan.identity)
        val stageDirectory = storage.resolve(stageRelative)
        require(!Files.exists(stageDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "A staging directory already exists for this operation attempt; reconcile it before reuse."
        }
        val journal = PublicationJournal(
            schemaVersion = JOURNAL_SCHEMA_VERSION,
            identity = plan.identity,
            state = PublicationJournalState.OPEN,
            expectedMetadataVersion = snapshot.metadataVersion,
            expectedCurrentResultSetId = snapshot.currentResult?.setId,
            setId = null,
            stageRelativeDirectory = stageRelative,
            finalRelativeDirectory = null,
            durability = EMPTY_DURABILITY,
            sequence = 0,
            updatedAtEpochMillis = clock(),
            detail = null,
        )
        // Persist intent before creating producer-visible staging. A process death
        // between these operations therefore leaves a recoverable journal, never
        // an untracked staging directory.
        storage.writeJournal(journal)
        Files.createDirectories(stageDirectory)
        faultInjector.after(PublicationBoundary.JOURNAL_OPENED)
        return ArtifactPublicationSession(this, plan, journal)
    }

    fun recoverAll(): PublicationRecoveryReport {
        var completed = 0
        var aborted = 0
        var stale = 0
        var unchanged = 0
        var corrupt = 0
        val details = ArrayList<String>()
        for (journalFile in storage.listJournalFiles()) {
            val journal = try {
                storage.readJournal(journalFile)
            } catch (failure: Exception) {
                corrupt++
                val quarantined = storage.quarantineJournal(journalFile, failure.javaClass.simpleName)
                details += "Quarantined corrupt journal $quarantined: ${failure.message ?: failure.javaClass.simpleName}"
                continue
            }
            if (journal.state.terminal) {
                unchanged++
                continue
            }
            try {
                val final = continuePublication(journal, recovering = true)
                when (final.state) {
                    PublicationJournalState.COMPLETED -> completed++
                    PublicationJournalState.ABORTED -> aborted++
                    PublicationJournalState.STALE -> stale++
                    else -> unchanged++
                }
                final.detail?.let { details += "${journal.identity.operationId}: $it" }
            } catch (failure: Exception) {
                val terminal = abortOrRecoverCommitted(
                    journal,
                    "Recovery failed safely: ${failure.message ?: failure.javaClass.simpleName}",
                )
                when (terminal.state) {
                    PublicationJournalState.COMPLETED -> completed++
                    PublicationJournalState.ABORTED -> aborted++
                    PublicationJournalState.STALE -> stale++
                    else -> unchanged++
                }
                terminal.detail?.let { details += "${journal.identity.operationId}: $it" }
            }
        }
        return PublicationRecoveryReport(completed, aborted, stale, unchanged, corrupt, details)
    }

    fun current(projectId: String): PublicationReadResult = readPointer(metadata.snapshot(projectId)?.currentResult)

    fun previous(projectId: String): PublicationReadResult = readPointer(metadata.snapshot(projectId)?.previousCompletedResult)

    /** Read a specific committed set, including an original-import batch. */
    fun committed(projectId: String, setId: String): PublicationReadResult {
        requireSafeToken(projectId, "projectId")
        requireSha256(setId, "setId")
        return readPointer(metadata.committedPointer(projectId, setId))
    }

    internal fun stage(
        session: ArtifactPublicationSession,
        logicalName: String,
        source: InputStream,
    ): ArtifactManifestEntry {
        synchronized(session.lock) {
            check(session.journal.state == PublicationJournalState.OPEN) { "Artifacts can only be staged while the session is open." }
            val declaration = session.plan.declarations.singleOrNull { it.logicalName == logicalName }
                ?: throw IllegalArgumentException("Unknown artifact declaration: $logicalName")
            require(logicalName !in session.staged) { "Artifact was already staged: $logicalName" }
            val entry = storage.writeArtifact(storage.resolve(session.journal.stageRelativeDirectory), declaration, source)
            session.staged[logicalName] = entry
            faultInjector.after(PublicationBoundary.ARTIFACT_SYNCED)
            return entry
        }
    }

    internal fun seal(session: ArtifactPublicationSession): ArtifactSetManifest {
        synchronized(session.lock) {
            if (session.journal.state != PublicationJournalState.OPEN) {
                val setId = requireNotNull(session.journal.setId) { "Sealed session has no artifact-set identity." }
                return storage.verifyArtifactSet(storage.resolve(session.journal.stageRelativeDirectory), setId).manifest
            }
            val entries = session.plan.declarations.map { declaration ->
                session.staged[declaration.logicalName] ?: ArtifactManifestEntry(
                    declaration = declaration,
                    presence = ArtifactPresence.ABSENT,
                    bytes = 0,
                    sha256 = null,
                )
            }
            val missing = entries.filter { it.declaration.required && it.presence != ArtifactPresence.PRESENT }
            require(missing.isEmpty()) { "Required artifacts were not staged: ${missing.joinToString { it.declaration.logicalName }}" }
            val manifest = PublicationCodec.createManifest(session.plan.identity, clock(), entries)
            val stageDirectory = storage.resolve(session.journal.stageRelativeDirectory)
            storage.writeManifest(stageDirectory, manifest)
            storage.verifyArtifactSet(stageDirectory, manifest.setId)
            val finalRelative = storage.finalRelativeDirectory(session.plan.identity, manifest.setId)
            val updated = updateJournal(
                session.journal,
                state = PublicationJournalState.SEALED,
                setId = manifest.setId,
                finalRelativeDirectory = finalRelative,
                durability = session.journal.durability.copy(
                    artifactContentsSynced = true,
                    manifestSynced = true,
                ),
                detail = null,
            )
            session.journal = updated
            faultInjector.after(PublicationBoundary.MANIFEST_SEALED)
            return manifest
        }
    }

    internal fun publish(session: ArtifactPublicationSession): VerifiedArtifactSet {
        synchronized(session.lock) {
            if (session.journal.state == PublicationJournalState.OPEN) seal(session)
            try {
                val terminal = continuePublication(session.journal, recovering = false)
                session.journal = terminal
                check(terminal.state == PublicationJournalState.COMPLETED) {
                    "Publication did not reach a completed state: ${terminal.state}"
                }
                val pointer = requireNotNull(metadata.committedPointer(
                    terminal.identity.projectId,
                    requireNotNull(terminal.setId),
                )) { "Metadata transaction did not retain the committed publication pointer." }
                return when (val read = readPointer(pointer)) {
                    is PublicationReadResult.Verified -> read.value
                    is PublicationReadResult.Invalid -> throw PublicationException(read.reason)
                    PublicationReadResult.None -> throw PublicationException("Committed publication pointer disappeared.")
                }
            } catch (failure: Exception) {
                session.journal = abortOrRecoverCommitted(
                    session.journal,
                    "Publication failed safely: ${failure.message ?: failure.javaClass.simpleName}",
                )
                throw failure
            }
        }
    }

    internal fun cancel(session: ArtifactPublicationSession, detail: String) {
        synchronized(session.lock) {
            if (!session.journal.state.terminal) session.journal = abortOrRecoverCommitted(session.journal, detail)
        }
    }

    private fun continuePublication(original: PublicationJournal, recovering: Boolean): PublicationJournal {
        var journal = original
        val committed = journal.setId?.let { metadata.committedPointer(journal.identity.projectId, it) }
        if (committed != null) {
            verifyPointer(committed)
            requireCommittedMetadataInvariant(journal, committed)
            journal = updateJournal(journal, PublicationJournalState.COMPLETED, detail = "Recovered already committed publication.")
            return journal
        }

        if (journal.state == PublicationJournalState.OPEN) {
            val stageDirectory = storage.resolve(journal.stageRelativeDirectory)
            val recoveredManifest = runCatching { storage.verifyArtifactSet(stageDirectory).manifest }.getOrNull()
            if (recoveredManifest == null || recoveredManifest.identity != journal.identity) {
                return abort(journal, "Open journal had no complete sealed manifest; staged files were discarded.")
            }
            val finalRelative = storage.finalRelativeDirectory(journal.identity, recoveredManifest.setId)
            journal = updateJournal(
                journal,
                PublicationJournalState.SEALED,
                setId = recoveredManifest.setId,
                finalRelativeDirectory = finalRelative,
                durability = journal.durability.copy(artifactContentsSynced = true, manifestSynced = true),
                detail = "Recovered a manifest written before the journal seal update.",
            )
        }

        val setId = requireNotNull(journal.setId) { "Non-open journal is missing setId." }
        val finalRelative = requireNotNull(journal.finalRelativeDirectory) { "Sealed journal is missing final directory." }
        val snapshot = metadata.snapshot(journal.identity.projectId)
            ?: return stale(journal, PublicationRejection.PROJECT_MISSING)
        val rejection = snapshotRejection(snapshot, journal.identity, journal.expectedCurrentResultSetId, journal.expectedMetadataVersion)
        if (rejection != null) return stale(journal, rejection)

        val stageDirectory = storage.resolve(journal.stageRelativeDirectory)
        val finalDirectory = storage.resolve(finalRelative)
        if (journal.state == PublicationJournalState.SEALED) {
            storage.verifyArtifactSet(stageDirectory, setId)
            journal = updateJournal(journal, PublicationJournalState.PROMOTING, detail = null)
            faultInjector.after(PublicationBoundary.PROMOTION_INTENT_SYNCED)
        }

        if (journal.state == PublicationJournalState.PROMOTING) {
            val durability = if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
                val finalVerified = storage.verifyArtifactSet(finalDirectory, setId)
                if (Files.exists(stageDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    val stageVerified = storage.verifyArtifactSet(stageDirectory, setId)
                    require(stageVerified.manifest == finalVerified.manifest) { "Stage/final manifests disagree after interrupted promotion." }
                    storage.deleteOwnedTree(stageDirectory)
                }
                journal.durability.copy(
                    artifactContentsSynced = true,
                    manifestSynced = true,
                    atomicDirectoryPromotion = true,
                )
            } else {
                storage.promoteDirectory(stageDirectory, finalDirectory)
            }
            storage.verifyArtifactSet(finalDirectory, setId)
            journal = updateJournal(
                journal,
                PublicationJournalState.FILES_PROMOTED,
                durability = durability,
                detail = if (recovering) "Recovered immutable artifact-set promotion." else null,
            )
            faultInjector.after(PublicationBoundary.DIRECTORY_PROMOTED)
        }

        if (journal.state == PublicationJournalState.FILES_PROMOTED) {
            val verified = storage.verifyArtifactSet(finalDirectory, setId)
            require(verified.manifest.identity == journal.identity) { "Promoted manifest identity does not match its journal." }
            val pointer = PublicationPointer(
                purpose = journal.identity.purpose,
                projectId = journal.identity.projectId,
                projectRevision = journal.identity.projectRevision,
                operationId = journal.identity.operationId,
                attempt = journal.identity.attempt,
                setId = setId,
                manifestSha256 = verified.manifestFileSha256,
                relativeDirectory = finalRelative,
                publishedAtEpochMillis = clock(),
                durability = journal.durability,
            )
            when (val result = metadata.commit(
                PublicationCommitRequest(
                    identity = journal.identity,
                    pointer = pointer,
                    expectedMetadataVersion = journal.expectedMetadataVersion,
                    expectedCurrentResultSetId = journal.expectedCurrentResultSetId,
                ),
            )) {
                is PublicationCommitResult.Committed -> {
                    require(result.committedPointer == pointer) { "Metadata port committed a different publication pointer." }
                    validateCommittedResult(journal, pointer, result)
                }
                is PublicationCommitResult.AlreadyCommitted -> {
                    require(result.pointer.setId == setId) { "Metadata port reported a different committed set." }
                    requireCommittedMetadataInvariant(journal, result.pointer)
                }
                is PublicationCommitResult.Rejected -> return stale(journal, result.reason)
            }
            journal = updateJournal(journal, PublicationJournalState.METADATA_COMMITTED, detail = null)
            faultInjector.after(PublicationBoundary.METADATA_COMMITTED)
        }

        if (journal.state == PublicationJournalState.METADATA_COMMITTED) {
            val pointer = metadata.committedPointer(journal.identity.projectId, setId)
                ?: throw PublicationException("Metadata commit is not visible during final reconciliation.")
            verifyPointer(pointer)
            requireCommittedMetadataInvariant(journal, pointer)
            journal = updateJournal(journal, PublicationJournalState.COMPLETED, detail = null)
        }
        return journal
    }

    private fun readPointer(pointer: PublicationPointer?): PublicationReadResult {
        if (pointer == null) return PublicationReadResult.None
        return try {
            PublicationReadResult.Verified(verifyPointer(pointer))
        } catch (failure: Exception) {
            PublicationReadResult.Invalid(failure.message ?: "Stored publication failed validation.")
        }
    }

    private fun verifyPointer(pointer: PublicationPointer): VerifiedArtifactSet {
        val directory = storage.resolve(pointer.relativeDirectory)
        val verified = storage.verifyArtifactSet(directory, pointer.setId, pointer.manifestSha256)
        val identity = verified.manifest.identity
        require(identity.purpose == pointer.purpose && identity.projectId == pointer.projectId &&
            identity.projectRevision == pointer.projectRevision && identity.operationId == pointer.operationId &&
            identity.attempt == pointer.attempt) { "Committed pointer identity does not match its manifest." }
        return VerifiedArtifactSet(pointer, verified.manifest, directory.toFile())
    }

    private fun requireSnapshot(identity: PublicationIdentity): ProjectPublicationSnapshot {
        val snapshot = metadata.snapshot(identity.projectId)
            ?: throw PublicationRejectedException(PublicationRejection.PROJECT_MISSING)
        val rejection = snapshotRejection(snapshot, identity, snapshot.currentResult?.setId, snapshot.metadataVersion)
        if (rejection != null) throw PublicationRejectedException(rejection)
        return snapshot
    }

    private fun validateSnapshot(
        snapshot: ProjectPublicationSnapshot,
        identity: PublicationIdentity,
        expectedCurrentSetId: String?,
        expectedMetadataVersion: Long,
    ) {
        snapshotRejection(snapshot, identity, expectedCurrentSetId, expectedMetadataVersion)?.let {
            throw PublicationRejectedException(it)
        }
    }

    private fun snapshotRejection(
        snapshot: ProjectPublicationSnapshot,
        identity: PublicationIdentity,
        expectedCurrentSetId: String?,
        expectedMetadataVersion: Long,
    ): PublicationRejection? = when {
        snapshot.deleted -> PublicationRejection.PROJECT_DELETED
        snapshot.projectRevision != identity.projectRevision -> PublicationRejection.REVISION_CHANGED
        snapshot.activeOperationId != identity.operationId -> PublicationRejection.OPERATION_CHANGED
        snapshot.activeAttempt != identity.attempt -> PublicationRejection.ATTEMPT_CHANGED
        snapshot.activeOperationState != DurableOperationState.RUNNING -> PublicationRejection.OPERATION_NOT_RUNNING
        snapshot.currentResult?.setId != expectedCurrentSetId -> PublicationRejection.CURRENT_RESULT_CHANGED
        snapshot.metadataVersion != expectedMetadataVersion -> PublicationRejection.METADATA_VERSION_CHANGED
        else -> null
    }

    private fun stale(journal: PublicationJournal, rejection: PublicationRejection): PublicationJournal {
        val terminal = updateJournal(
            journal,
            PublicationJournalState.STALE,
            detail = "Publication rejected without replacing the last completed result: $rejection",
        )
        cleanupUnreferenced(terminal)
        return terminal
    }

    private fun abort(journal: PublicationJournal, detail: String): PublicationJournal {
        if (journal.state.terminal) return journal
        val terminal = updateJournal(journal, PublicationJournalState.ABORTED, detail = detail.take(512))
        cleanupUnreferenced(terminal)
        return terminal
    }

    /**
     * A local metadata implementation can commit its transaction and still fail
     * while returning to the caller. Never delete or label that set aborted until
     * the committed-pointer lookup proves it was not accepted.
     */
    private fun abortOrRecoverCommitted(journal: PublicationJournal, detail: String): PublicationJournal {
        val setId = journal.setId
        if (setId != null) {
            val committed = try {
                metadata.committedPointer(journal.identity.projectId, setId)
            } catch (lookupFailure: Exception) {
                // Commit outcome is ambiguous. Preserve both staged/final bytes
                // and the nonterminal journal so a later recovery can decide.
                return updateJournal(
                    journal,
                    journal.state,
                    detail = "Publication outcome could not be established; retained for recovery: ${lookupFailure.message}".take(512),
                )
            }
            if (committed != null) {
                verifyPointer(committed)
                return try {
                    requireCommittedMetadataInvariant(journal, committed)
                    updateJournal(
                        journal,
                        PublicationJournalState.COMPLETED,
                        detail = "Metadata commit was already durable; reconciled after caller failure.",
                    )
                } catch (invariantFailure: Exception) {
                    // The committed set must remain present because metadata still
                    // references it, but this is not a successful publication.
                    updateJournal(
                        journal,
                        PublicationJournalState.ABORTED,
                        detail = "Committed metadata violated the publication contract: ${invariantFailure.message}".take(512),
                    )
                }
            }
        }
        return abort(journal, detail)
    }

    private fun validateCommittedResult(
        journal: PublicationJournal,
        pointer: PublicationPointer,
        result: PublicationCommitResult.Committed,
    ) {
        when (journal.identity.purpose) {
            PublicationPurpose.RESULT_SET -> {
                require(result.currentResult == pointer) { "Result publication was not installed as current." }
                val oldCurrent = journal.expectedCurrentResultSetId
                if (oldCurrent != null) {
                    require(result.previousCompletedResult?.setId == oldCurrent) {
                        "Metadata transaction did not preserve the previous completed result."
                    }
                }
            }
            PublicationPurpose.ORIGINAL_IMPORT -> {
                require(result.currentResult?.setId != pointer.setId) {
                    "An original-import artifact set cannot be exposed as a reconstruction result."
                }
                val oldCurrent = journal.expectedCurrentResultSetId
                if (oldCurrent != null) {
                    require(result.previousCompletedResult?.setId == oldCurrent) {
                        "Input publication did not retain the last completed result as previous."
                    }
                }
            }
        }
    }

    private fun requireCommittedMetadataInvariant(
        journal: PublicationJournal,
        pointer: PublicationPointer,
    ) {
        val snapshot = metadata.snapshot(journal.identity.projectId)
            ?: throw PublicationException("Committed publication project disappeared.")
        when (journal.identity.purpose) {
            PublicationPurpose.RESULT_SET -> {
                require(snapshot.currentResult?.setId == pointer.setId) {
                    "Committed result is not the project's current verified result."
                }
                journal.expectedCurrentResultSetId?.let { previousSetId ->
                    require(snapshot.previousCompletedResult?.setId == previousSetId) {
                        "The last completed result was not retained as previous."
                    }
                }
            }
            PublicationPurpose.ORIGINAL_IMPORT -> {
                require(snapshot.currentResult?.setId != pointer.setId) {
                    "Committed original import was exposed as a reconstruction result."
                }
                journal.expectedCurrentResultSetId?.let { previousSetId ->
                    require(snapshot.previousCompletedResult?.setId == previousSetId) {
                        "The input mutation did not retain the last completed result."
                    }
                }
            }
        }
    }

    private fun cleanupUnreferenced(journal: PublicationJournal) {
        val stage = storage.resolve(journal.stageRelativeDirectory)
        if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) storage.deleteOwnedTree(stage)
        val setId = journal.setId ?: return
        if (metadata.committedPointer(journal.identity.projectId, setId) != null) return
        journal.finalRelativeDirectory?.let { relative ->
            val final = storage.resolve(relative)
            if (Files.exists(final, LinkOption.NOFOLLOW_LINKS)) storage.deleteOwnedTree(final)
        }
    }

    private fun updateJournal(
        journal: PublicationJournal,
        state: PublicationJournalState,
        setId: String? = journal.setId,
        finalRelativeDirectory: String? = journal.finalRelativeDirectory,
        durability: PublicationDurability = journal.durability,
        detail: String? = journal.detail,
    ): PublicationJournal {
        val updated = journal.copy(
            state = state,
            setId = setId,
            finalRelativeDirectory = finalRelativeDirectory,
            durability = durability,
            sequence = journal.sequence + 1,
            updatedAtEpochMillis = clock(),
            detail = detail,
        )
        storage.writeJournal(updated)
        return updated
    }

    private companion object {
        const val RESERVED_MANIFEST_PATH = ".publication-manifest.rfpm"
        val EMPTY_DURABILITY = PublicationDurability(
            artifactContentsSynced = false,
            manifestSynced = false,
            atomicDirectoryPromotion = false,
            stagingDirectorySynced = false,
            destinationDirectorySynced = false,
        )
    }
}

class ArtifactPublicationSession internal constructor(
    private val coordinator: ArtifactPublicationCoordinator,
    internal val plan: PublicationPlan,
    internal var journal: PublicationJournal,
) {
    internal val lock = Any()
    internal val staged = LinkedHashMap<String, ArtifactManifestEntry>()

    val identity: PublicationIdentity get() = plan.identity
    val state: PublicationJournalState get() = synchronized(lock) { journal.state }

    fun stage(logicalName: String, source: InputStream): ArtifactManifestEntry = coordinator.stage(this, logicalName, source)

    fun stageBytes(logicalName: String, bytes: ByteArray): ArtifactManifestEntry =
        ByteArrayInputStream(bytes).use { coordinator.stage(this, logicalName, it) }

    fun stageFile(logicalName: String, source: File): ArtifactManifestEntry {
        require(source.isFile) { "Producer artifact is missing: ${source.path}" }
        return FileInputStream(source).use { coordinator.stage(this, logicalName, it) }
    }

    fun seal(): ArtifactSetManifest = coordinator.seal(this)

    fun publish(): VerifiedArtifactSet = coordinator.publish(this)

    fun cancel(detail: String = "Publication cancelled before metadata commit.") = coordinator.cancel(this, detail)
}
