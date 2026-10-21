package com.realityforge.scanner.capture.calibration

import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.data.reopening.BoundedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.coroutines.coroutineContext

enum class CalibrationMutationBoundary { STAGED, PROFILE_SWITCHED, METADATA_COMMITTED }
fun interface CalibrationMutationFaultInjector { fun after(boundary: CalibrationMutationBoundary) }
data class CalibrationRecoveryReport(val committed: Int, val rolledBack: Int, val failures: List<String>)

/**
 * Caller holds the C6 MUTATE_INPUTS lease across stage/commit/abort; startup recovery runs before admission.
 * The previous profile is retained until Room records the revision change. Recovery compares exact content
 * identities, restoring the old optical model whenever the database commit did not happen.
 */
class JournaledCalibrationProfilePublicationPort(
    private val paths: ProjectPathPolicy,
    private val metadata: CalibrationMutationStore,
    private val faults: CalibrationMutationFaultInjector = CalibrationMutationFaultInjector {},
) : CalibrationProfilePublicationPort {
    override suspend fun stage(request: CalibrationPublicationRequest): CalibrationProfilePublication = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val profileBytes = request.encodedProfile?.clone()
        if (profileBytes != null) {
            require(profileBytes.size <= CalibrationProfileCodec.MAX_PROFILE_BYTES)
            require(CalibrationProfileCodec.decode(profileBytes.toString(Charsets.UTF_8)).profileId == request.profileId)
        }
        val digest = profileBytes?.let(::digest)
        val record = CalibrationMutationEntity(request.key.operationId, request.key.projectId,
            request.key.expectedRevision, request.kind.name, request.profileId, digest)
        val recovery = recover(request.key.projectId)
        check(recovery.failures.isEmpty()) { "Previous calibration publication needs recovery: ${recovery.failures.joinToString()}" }
        val preparedResult = metadata.prepare(record)
        if (preparedResult != null) return@withContext TerminalPublication(preparedResult)
        val directory = operationDirectory(request.key.projectId, request.key.operationId)
        try {
            check(!directory.exists() && directory.mkdir()) { "Calibration staging already exists; recover before retrying." }
            val current = profileFile(request.key.projectId)
            val old = if (Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS)) readProfile(current) else null
            old?.let { writeSynced(File(directory, OLD_PROFILE), it) }
            profileBytes?.let { writeSynced(File(directory, NEW_PROFILE), it) }
            val journal = JSONObject().put("schema", 1).put("projectId", record.projectId)
                .put("operationId", record.operationId).put("expectedRevision", record.expectedRevision)
                .put("kind", record.kind).put("profileId", record.profileId ?: JSONObject.NULL)
                .put("targetSha256", record.targetSha256 ?: JSONObject.NULL)
                .put("previousSha256", old?.let(::digest) ?: JSONObject.NULL)
            writeSynced(File(directory, JOURNAL), journal.toString().toByteArray(Charsets.UTF_8))
            syncDirectory(directory); syncDirectory(requireNotNull(directory.parentFile))
            faults.after(CalibrationMutationBoundary.STAGED)
            Publication(record, directory)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (File(directory, JOURNAL).isFile) reconcile(directory, record.projectId)
                else { if (directory.exists()) check(directory.deleteRecursively()); metadata.discardUncommitted(record.operationId) }
            }
            throw failure
        }
    }

    private inner class Publication(private val record: CalibrationMutationEntity, private val directory: File) : CalibrationProfilePublication {
        override suspend fun commit(): CalibrationProfileStoreResult = withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            metadata.load(record.operationId)?.committedResult()?.let { return@withContext it }
            val journal = readJournal(directory, record.projectId)
            require(journal.record == record)
            val current = profileFile(record.projectId)
            require(currentDigest(current) == journal.previousSha256) { "Current optical model changed before publication." }
            withContext(NonCancellable) {
                if (record.kind == CalibrationProfileMutationKind.SAVE.name) {
                    val staged = File(directory, NEW_PROFILE)
                    require(digest(readProfile(staged)) == record.targetSha256)
                    Files.move(staged.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else Files.deleteIfExists(current.toPath())
                syncDirectory(requireNotNull(current.parentFile))
                faults.after(CalibrationMutationBoundary.PROFILE_SWITCHED)
                val result = metadata.commit(record.operationId)
                faults.after(CalibrationMutationBoundary.METADATA_COMMITTED)
                reconcile(directory, record.projectId)
                result
            }
        }

        override suspend fun abort() = withContext(Dispatchers.IO + NonCancellable) {
            if (directory.isDirectory) reconcile(directory, record.projectId)
            Unit
        }
    }

    suspend fun recover(projectId: String): CalibrationRecoveryReport = withContext(Dispatchers.IO) {
        val root = journalRoot(projectId)
        var committed = 0; var rolledBack = 0
        val failures = mutableListOf<String>()
        for (directory in root.listFiles().orEmpty()) {
            coroutineContext.ensureActive()
            if (!directory.name.matches(Regex("[0-9a-f]{64}")) || !directory.isDirectory || Files.isSymbolicLink(directory.toPath())) continue
            try {
                if (!File(directory, JOURNAL).isFile) {
                    // A journal is the ownership proof for metadata/file publication. Unmarked staging is never current.
                    check(directory.deleteRecursively())
                    rolledBack++
                } else if (reconcile(directory, projectId)) committed++ else rolledBack++
            } catch (failure: Exception) {
                failures += "${directory.name}: ${failure.message ?: "Profile recovery failed."}"
            }
        }
        // A crash may occur after the Room preparation but before the first directory or journal write.
        // Commit always requires a complete journal, so an absent operation directory proves no switch is pending.
        for (record in metadata.pending(projectId)) {
            if (!operationDirectory(projectId, record.operationId).exists()) metadata.discardUncommitted(record.operationId)
        }
        CalibrationRecoveryReport(committed, rolledBack, failures.toList())
    }

    private suspend fun reconcile(directory: File, projectId: String): Boolean {
        val journal = readJournal(directory, projectId)
        val record = metadata.load(journal.record.operationId)
        require(record == null || record.copy(committedRevision = null) == journal.record) { "Calibration journal identity differs from Room." }
        val current = profileFile(projectId)
        val actual = currentDigest(current)
        val committed = record?.committedRevision != null
        if (committed) {
            require(actual == journal.record.targetSha256) { "Committed optical profile failed integrity; retained its recovery record." }
        } else if (actual != journal.previousSha256) {
            require(actual == journal.record.targetSha256) { "Unrelated optical profile detected; no files were changed." }
            if (journal.previousSha256 == null) Files.deleteIfExists(current.toPath())
            else {
                val backup = File(directory, OLD_PROFILE)
                require(digest(readProfile(backup)) == journal.previousSha256) { "Previous profile backup failed integrity." }
                Files.move(backup.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(requireNotNull(current.parentFile))
        }
        require(currentDigest(current) == if (committed) journal.record.targetSha256 else journal.previousSha256)
        check(directory.deleteRecursively()) { "Calibration staging cleanup needs a retry." }
        syncDirectory(requireNotNull(directory.parentFile))
        if (!committed) metadata.discardUncommitted(journal.record.operationId)
        return committed
    }

    private fun readJournal(directory: File, projectId: String): Journal {
        require(!Files.isSymbolicLink(directory.toPath()))
        val file = File(directory, JOURNAL)
        require(file.isFile && !Files.isSymbolicLink(file.toPath()))
        val json = file.inputStream().use { BoundedJson.read(it, 4096) }
        require(json.get("schema") == 1 && json.getString("projectId") == projectId)
        val operationId = json.getString("operationId")
        require(directory.name == sha256Utf8(operationId))
        val rawRevision = json.get("expectedRevision")
        require(rawRevision is Int || rawRevision is Long)
        val record = CalibrationMutationEntity(operationId, projectId, (rawRevision as Number).toLong(), json.getString("kind"),
            if (json.isNull("profileId")) null else json.getString("profileId"),
            if (json.isNull("targetSha256")) null else json.getString("targetSha256"))
        val previous = if (json.isNull("previousSha256")) null else json.getString("previousSha256").also(::requireSha256)
        return Journal(record, previous)
    }

    private fun journalRoot(projectId: String): File = paths.projectFile(projectId, ".calibration-publications").also {
        check(it.isDirectory || it.mkdir()) { "Could not create calibration publication storage." }
    }
    private fun operationDirectory(projectId: String, operationId: String) = File(journalRoot(projectId), sha256Utf8(operationId))
    private fun profileFile(projectId: String) = paths.projectFile(projectId, "camera-calibration-v1.json")
    private fun currentDigest(file: File): String? = if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null else digest(readProfile(file))
    private fun readProfile(file: File): ByteArray {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..CalibrationProfileCodec.MAX_PROFILE_BYTES.toLong()) {
            "Optical profile is missing or outside the supported size."
        }
        return file.readBytes().also { require(it.size <= CalibrationProfileCodec.MAX_PROFILE_BYTES) }
    }
    private data class Journal(val record: CalibrationMutationEntity, val previousSha256: String?)
    private class TerminalPublication(private val result: CalibrationProfileStoreResult) : CalibrationProfilePublication {
        override suspend fun commit() = result
        override suspend fun abort() = Unit
    }

    companion object {
        private const val JOURNAL = "intent.json"
        private const val OLD_PROFILE = "previous.json"
        private const val NEW_PROFILE = "next.json"
        private fun digest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
        private fun syncDirectory(directory: File) =
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
