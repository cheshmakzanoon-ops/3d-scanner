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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.DigestInputStream
import java.security.MessageDigest
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
            if (before.state in setOf("DELETING", "DELETING_PHOTO", "DELETED")) return@withContext ReopenResult.Changed to null
            val resultRevision = before.resultRevision ?: return@withContext ReopenResult.Missing to null
            require(resultRevision <= before.revision) { "Saved result is from an impossible future revision." }
            val stale = resultRevision != before.revision
            if (!previous && stale) return@withContext ReopenResult.Missing to null
            // Input edits retain the latest completed set in the current publication slot. In that
            // state it is the user's previous result; selecting previousCompletedResult would skip
            // it and display an older, penultimate reconstruction instead.
            val usePreviousHead = previous && !stale
            val published = if (usePreviousHead) publications?.previous(projectId) else publications?.current(projectId)
            if (published is PublicationReadResult.Invalid)
                return@withContext ReopenResult.Invalid("invalid_artifact_set", published.reason.take(300)) to null
            val verified = (published as? PublicationReadResult.Verified)?.value
            if (usePreviousHead && verified == null) return@withContext ReopenResult.Missing to null
            val key = before.resultKey ?: return@withContext ReopenResult.Missing to null
            val revision = verified?.manifest?.identity?.projectRevision ?: resultRevision
            if (verified != null) {
                require(verified.manifest.identity.projectId == projectId && verified.pointer.projectId == projectId)
                require(verified.pointer.projectRevision == revision && revision <= before.revision)
                if (!usePreviousHead) require(revision == resultRevision) { "Saved result pointer disagrees with the project." }
            }
            val file = if (verified != null) {
                verified.artifact(previewLogicalName)
                    ?: return@withContext ReopenResult.Invalid("missing_preview", "Completed artifact set has no preview.") to null
            } else {
                // Native schemas 1/3/6 are supported as explicit legacy layouts; identity still must agree.
                check(paths.artifactRevision(key) == revision) { "Saved preview revision is inconsistent." }
                paths.artifact(projectId, key, "json")
            }
            require(paths.requireOwnedFile(file).projectId == projectId)
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 2..PreviewSchemaValidator.MAX_BYTES.toLong()) {
                "Saved preview is missing or exceeds its memory budget."
            }
            val expectedEntry = verified?.manifest?.entries?.single { it.declaration.logicalName == previewLogicalName }
            if (expectedEntry != null) require(file.length() == expectedEntry.bytes) { "Preview length changed after set verification." }
            val digest = MessageDigest.getInstance("SHA-256")
            val json = DigestInputStream(file.inputStream(), digest).use {
                BoundedJson.read(it, PreviewSchemaValidator.MAX_BYTES) { scope.ensureActive() }
            }
            if (expectedEntry != null) require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedEntry.sha256) {
                "Preview bytes changed after set verification."
            }
            val expectedKey = if (usePreviousHead && verified != null) {
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
            if (validated is ReopenResult.Ready) {
                validated.copy(publicationSetId = verified?.manifest?.setId) to json
            } else validated to null
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
        } catch (failure: Exception) {
            ReopenResult.Invalid("preview_load_failed", failure.message?.take(300) ?: "Saved preview could not be loaded.") to null
        }
    }
}

class InvalidPreviewException(val outcome: ReopenResult.Invalid) : IllegalStateException(outcome.detail)
