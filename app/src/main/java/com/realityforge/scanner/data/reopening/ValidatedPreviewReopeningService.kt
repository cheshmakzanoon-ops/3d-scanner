package com.realityforge.scanner.data.reopening

import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.operations.PreviewReopeningService
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.data.publication.VerifiedArtifactSetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** C6 compatibility returns JSON only after the same strict typed validation used by new readers. */
class ValidatedPreviewReopeningService(
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
    private val publications: VerifiedArtifactSetRepository? = null,
    private val previewLogicalName: String = "preview",
) : PreviewReopeningService {
    override suspend fun currentPreview(projectId: String): JSONObject? {
        val (outcome, json) = load(projectId, previous = false)
        return when (outcome) {
            is ReopenResult.Ready -> json
            is ReopenResult.Invalid -> throw InvalidPreviewException(outcome)
            ReopenResult.Changed, ReopenResult.Missing -> null
        }
    }

    suspend fun reopen(projectId: String, previous: Boolean = false): ReopenResult = load(projectId, previous).first

    private suspend fun load(projectId: String, previous: Boolean): Pair<ReopenResult, JSONObject?> = withContext(Dispatchers.IO) {
        val before = dao.project(projectId) ?: return@withContext ReopenResult.Missing to null
        val scope = coroutineContext
        try {
            val published = if (previous) publications?.previous(projectId) else publications?.current(projectId)
            if (published is PublicationReadResult.Invalid)
                return@withContext ReopenResult.Invalid("invalid_artifact_set", published.reason.take(300)) to null
            val verified = (published as? PublicationReadResult.Verified)?.value
            if (previous && verified == null) return@withContext ReopenResult.Missing to null
            if (!previous && before.resultRevision != before.revision) return@withContext ReopenResult.Missing to null
            val key = before.resultKey ?: return@withContext ReopenResult.Missing to null
            val revision = verified?.manifest?.identity?.projectRevision ?: before.revision
            val file = if (verified != null) {
                verified.artifact(previewLogicalName)
                    ?: return@withContext ReopenResult.Invalid("missing_preview", "Completed artifact set has no preview.") to null
            } else {
                // Native schemas 1/3/6 are supported as explicit legacy layouts; identity still must agree.
                check(paths.artifactRevision(key) == revision) { "Saved preview revision is inconsistent." }
                paths.artifact(projectId, key, "json")
            }
            require(file.isFile && file.length() in 2..PreviewSchemaValidator.MAX_BYTES.toLong()) {
                "Saved preview is missing or exceeds its memory budget."
            }
            val json = file.inputStream().use { BoundedJson.read(it, PreviewSchemaValidator.MAX_BYTES) { scope.ensureActive() } }
            val expectedKey = if (previous && verified != null) {
                // The immutable set verifies bytes; accept its encoded legacy key only at the pointer revision.
                val stored = json.get("artifactKey") as? String ?: error("Saved preview has no artifact key.")
                check(paths.artifactRevision(stored) == revision)
                stored
            } else key
            val validated = PreviewSchemaValidator.validate(json, revision, expectedKey, previous) { scope.ensureActive() }
            val after = dao.project(projectId)
            if (after == null || after.revision != before.revision || after.resultKey != before.resultKey ||
                after.resultRevision != before.resultRevision) return@withContext ReopenResult.Changed to null
            scope.ensureActive()
            validated to if (validated is ReopenResult.Ready) json else null
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
        } catch (failure: Exception) {
            ReopenResult.Invalid("preview_load_failed", failure.message?.take(300) ?: "Saved preview could not be loaded.") to null
        }
    }
}

class InvalidPreviewException(val outcome: ReopenResult.Invalid) : IllegalStateException(outcome.detail)
