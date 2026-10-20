package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.jobs.ReconstructionJobEventEntity
import com.realityforge.scanner.data.publication.*
import com.realityforge.scanner.data.reopening.BoundedJson
import com.realityforge.scanner.data.reopening.PreviewSchemaValidator
import com.realityforge.scanner.data.reopening.ReopenResult
import java.io.File
import java.nio.file.Files

/** Every set, journal and staging directory belongs to the project deletion boundary. */
class ProjectPublicationService(
    private val database: ScanDatabase,
    private val paths: ProjectPathPolicy,
) : ArtifactPublicationSessionFactory, VerifiedArtifactSetRepository {
    private val execution get() = database.executionPublicationDao()
    private val metadata = RoomPublicationMetadataPort(database, database.publicationHeadDao(),
        PublicationOperationReader { projectId ->
            execution.active(projectId, System.currentTimeMillis())?.let {
                ActivePublicationOperation(it.requestId, it.attempt, DurableOperationState.RUNNING)
            }
        }, onResultCommitted = ::commitExecution)

    private fun gateway(projectId: String): DefaultPublicationGateway {
        val root = File(paths.projectDirectory(projectId), DIRECTORY)
        paths.requireOwnedFile(root)
        return DefaultPublicationGateway(ArtifactPublicationCoordinator(root, metadata))
    }

    override fun beginResult(identity: PublicationIdentity, declarations: List<ArtifactDeclaration>) =
        gateway(identity.projectId).beginResult(identity, declarations)

    override fun beginOriginalImport(identity: PublicationIdentity, declarations: List<ArtifactDeclaration>): ArtifactPublicationSession =
        error("Original imports use the revision-fenced original-photo journal.")

    override fun current(projectId: String): PublicationReadResult = gateway(projectId).current(projectId)
    override fun previous(projectId: String): PublicationReadResult = gateway(projectId).previous(projectId)
    override fun committed(projectId: String, setId: String): PublicationReadResult = gateway(projectId).committed(projectId, setId)

    override fun reconcile(): PublicationRecoveryReport {
        val reports = execution.projectIds().map { gateway(it).reconcile() }
        return PublicationRecoveryReport(reports.sumOf { it.completed }, reports.sumOf { it.aborted },
            reports.sumOf { it.stale }, reports.sumOf { it.unchanged }, reports.sumOf { it.corruptJournals },
            reports.flatMap { it.details }.take(256))
    }

    /** Invoked by owner023 inside the SAME transaction as current/previous pointers and receipt. */
    private fun commitExecution(request: PublicationCommitRequest) {
        val identity = request.identity
        val now = System.currentTimeMillis()
        val job = requireNotNull(execution.active(identity.projectId, now)) { "Publication job lease expired." }
        check(job.requestId == identity.operationId && job.attempt == identity.attempt &&
            job.projectRevision == identity.projectRevision && job.inputSha256 == identity.inputIdentitySha256 &&
            job.settingsSha256 == identity.settingsIdentitySha256 && job.engineVersion == identity.engineVersion) {
            "Publication identity differs from the executing job."
        }
        val preview = File(File(paths.projectDirectory(identity.projectId), DIRECTORY),
            "${request.pointer.relativeDirectory}/preview.json")
        paths.requireOwnedFile(preview)
        check(!Files.isSymbolicLink(preview.toPath()) && preview.isFile &&
            preview.length() in 2..PreviewSchemaValidator.MAX_BYTES.toLong()) { "Published preview exceeds its bound." }
        val json = preview.inputStream().use { BoundedJson.read(it, PreviewSchemaValidator.MAX_BYTES) }
        val key = json.getString("artifactKey")
        check(paths.artifactRevision(key) == identity.projectRevision)
        check(PreviewSchemaValidator.validate(json, identity.projectRevision, key) is ReopenResult.Ready) {
            "Published preview failed its typed geometry contract."
        }
        check(execution.publishProject(identity.projectId, identity.projectRevision, key,
            "${json.getInt("pointCount")} sparse points; completed artifact set verified.") == 1)
        check(execution.complete(job.jobId, job.attempt, job.stateVersion, requireNotNull(job.leaseToken),
            request.pointer.setId, request.pointer.manifestSha256, now) == 1) { "Publication attempt changed." }
        execution.event(ReconstructionJobEventEntity(jobId = job.jobId, attempt = job.attempt,
            kind = "COMPLETED", state = "COMPLETED", stage = "PUBLISHING", stateVersion = job.stateVersion + 1,
            code = null, progressStage = null, progressCompletedUnits = null, progressTotalUnits = null,
            progressUnit = null, createdAtEpochMillis = now))
        execution.trimEvents(job.jobId)
    }

    companion object { const val DIRECTORY = "publications-v1" }
}
