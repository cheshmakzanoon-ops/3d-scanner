package com.realityforge.scanner.data.publication

/** Producer-facing adapter for Agent 002 pipeline orchestration and staged imports. */
interface ArtifactPublicationSessionFactory {
    fun beginResult(identity: PublicationIdentity, declarations: List<ArtifactDeclaration>): ArtifactPublicationSession
    fun beginOriginalImport(identity: PublicationIdentity, declarations: List<ArtifactDeclaration>): ArtifactPublicationSession
}

/** Repository-facing adapter for Agent 006 and read-only consumers 024/026. */
interface VerifiedArtifactSetRepository {
    fun reconcile(): PublicationRecoveryReport
    fun current(projectId: String): PublicationReadResult
    fun previous(projectId: String): PublicationReadResult
    fun committed(projectId: String, setId: String): PublicationReadResult
}

class DefaultPublicationGateway(
    private val coordinator: ArtifactPublicationCoordinator,
) : ArtifactPublicationSessionFactory, VerifiedArtifactSetRepository {
    override fun beginResult(
        identity: PublicationIdentity,
        declarations: List<ArtifactDeclaration>,
    ): ArtifactPublicationSession {
        require(identity.purpose == PublicationPurpose.RESULT_SET) { "Result adapter requires RESULT_SET identity." }
        return coordinator.begin(PublicationPlan(identity, declarations))
    }

    override fun beginOriginalImport(
        identity: PublicationIdentity,
        declarations: List<ArtifactDeclaration>,
    ): ArtifactPublicationSession {
        require(identity.purpose == PublicationPurpose.ORIGINAL_IMPORT) { "Import adapter requires ORIGINAL_IMPORT identity." }
        require(declarations.all { it.representation == REPRESENTATION_ORIGINAL_PHOTO || it.representation == REPRESENTATION_IMPORT_METADATA }) {
            "Original-import sessions may contain only original photographs and import metadata."
        }
        return coordinator.begin(PublicationPlan(identity, declarations))
    }

    override fun reconcile(): PublicationRecoveryReport = coordinator.recoverAll()
    override fun current(projectId: String): PublicationReadResult = coordinator.current(projectId)
    override fun previous(projectId: String): PublicationReadResult = coordinator.previous(projectId)
    override fun committed(projectId: String, setId: String): PublicationReadResult = coordinator.committed(projectId, setId)

    companion object {
        const val REPRESENTATION_ORIGINAL_PHOTO = "original-photo"
        const val REPRESENTATION_IMPORT_METADATA = "import-metadata"
    }
}
