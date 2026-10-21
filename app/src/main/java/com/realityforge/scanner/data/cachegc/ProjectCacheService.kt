package com.realityforge.scanner.data.cachegc

import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.jobs.ReconstructionJobDao
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.data.publication.ArtifactPresence
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.data.publication.VerifiedArtifactSet
import com.realityforge.scanner.data.publication.VerifiedArtifactSetRepository
import com.realityforge.scanner.engine.artifacts.PipelineStage
import com.realityforge.scanner.engine.artifacts.StageManifest
import com.realityforge.scanner.engine.artifacts.StageManifestCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class CacheReferenceData(
    val projectRevision: Long,
    val stageManifests: List<StageManifest>,
    val publishedSets: List<VerifiedArtifactSet>,
    val protections: CacheProtectionSnapshot,
)

fun interface CacheReferenceSource { suspend fun read(projectId: String): CacheReferenceData }

/** Persist the existing owner-009 wire format, rather than a parallel filename-based cache index. */
class CacheStageManifestStore(private val projectDirectory: File) {
    fun record(manifest: StageManifest) {
        require(manifest.binding.projectId == projectDirectory.name)
        val directory = File(projectDirectory, DIRECTORY)
        check(directory.exists() || directory.mkdirs())
        check(!Files.isSymbolicLink(directory.toPath()))
        val bytes = StageManifestCodec.encode(manifest)
        val target = File(directory, "${CacheHashes.sha256(bytes)}.rfsm")
        if (target.isFile) { check(target.readBytes().contentEquals(bytes)); return }
        val temporary = File.createTempFile("manifest-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun read(): List<StageManifest> {
        val directory = File(projectDirectory, DIRECTORY)
        if (!directory.exists()) return emptyList()
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        val files = Files.newDirectoryStream(directory.toPath()).use {
            it.asSequence().take(MAX_MANIFESTS + 1).toList()
        }
        require(files.size <= MAX_MANIFESTS) { "Stage manifest inventory exceeds its supported bound." }
        return files.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.rfsm")) }
            .sortedBy { it.fileName.toString() }.map { path ->
                check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                require(Files.size(path) in 1..StageManifestCodec.MAX_MANIFEST_BYTES.toLong())
                val bytes = Files.readAllBytes(path)
                check(path.fileName.toString() == "${CacheHashes.sha256(bytes)}.rfsm")
                StageManifestCodec.decode(bytes).also {
                    check(it.binding.projectId == projectDirectory.name)
                }
            }
    }

    companion object { private const val DIRECTORY = "cache-stage-manifests-v1"; private const val MAX_MANIFESTS = 2048 }
}

/** Actual job/manifest/publication adapter. Corrupt references stop cleanup; unknown files survive. */
class RepositoryCacheReferenceSource(
    private val repository: ProjectRepository,
    private val jobDao: ReconstructionJobDao,
    private val publications: VerifiedArtifactSetRepository,
) : CacheReferenceSource {
    override suspend fun read(projectId: String): CacheReferenceData {
        val project = requireNotNull(repository.dao.project(projectId))
        val root = repository.directory(projectId).toPath().toAbsolutePath().normalize()
        val jobs = jobDao.activeForProject(projectId)
        val manifests = CacheStageManifestStore(root.toFile()).read()
        val sets = listOf(publications.current(projectId), publications.previous(projectId)).mapNotNull {
            when (it) {
                is PublicationReadResult.Verified -> it.value
                PublicationReadResult.None -> null
                else -> error("Completed publication references require recovery before cache cleanup.")
            }
        }.distinctBy { it.manifest.setId }
        val exact = LinkedHashMap<String, Set<CacheProtectionReason>>()
        repository.dao.photos(projectId).forEach { photo ->
            exact[CachePathPolicy.fromRoot(root, repository.photoFile(photo).toPath())] =
                setOf(CacheProtectionReason.ORIGINAL_INPUT)
        }
        val activeIds = jobs.map { it.jobId }.toSet()
        manifests.filter { it.binding.jobId in activeIds }.forEach { manifest ->
            manifest.artifacts.forEach { artifact -> exact[artifact.relativePath] =
                exact[artifact.relativePath].orEmpty() + CacheProtectionReason.ACTIVE_JOB }
        }
        sets.forEach { set ->
            set.manifest.entries.filter { it.presence == ArtifactPresence.PRESENT }.forEach { entry ->
                val file = File(set.directory, entry.declaration.relativePath)
                exact[CachePathPolicy.fromRoot(root, file.toPath())] = setOf(CacheProtectionReason.RETAINED_RESULT)
            }
        }
        val generationInput = buildString {
            append(project.revision).append('|').append(project.resultKey).append('|')
            jobs.sortedBy { it.jobId }.forEach { append(it.jobId).append(':').append(it.stateVersion).append('|') }
            sets.sortedBy { it.manifest.setId }.forEach { append(it.manifest.setId).append('|') }
            manifests.forEach { append(CacheHashes.sha256(StageManifestCodec.encode(it))).append('|') }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(generationInput.toByteArray())
        val generation = ByteBuffer.wrap(digest).long and Long.MAX_VALUE
        return CacheReferenceData(project.revision, manifests, sets,
            CacheProtectionSnapshot(projectId, generation, activeIds, exact,
                prefixPaths = mapOf("originals" to setOf(CacheProtectionReason.ORIGINAL_INPUT))))
    }
}

class ProjectCacheService(
    private val repository: ProjectRepository,
    private val referenceSource: CacheReferenceSource,
    private val journalRoot: File,
    private val policy: CacheRetentionPolicy = CacheRetentionPolicy(),
) {
    suspend fun projectInventory(projectId: String): Pair<CacheInventory, CleanupPlan> = withContext(Dispatchers.IO) {
        repository.withProjectAccess(ProjectOperationRequest.create(projectId, ProjectOperationKind.CLEANUP)) {
            val references = referenceSource.read(projectId)
            val inventory = inventory(projectId, references)
            inventory to CacheRetentionPlanner(policy).plan(inventory)
        }
    }

    suspend fun cleanup(projectId: String, plan: CleanupPlan,
        cancellation: CacheCleanupCancellation = CacheCleanupCancellation.NEVER): CleanupExecutionResult =
        withContext(Dispatchers.IO) {
            require(plan.projectId == projectId)
            repository.withProjectAccess(ProjectOperationRequest.create(projectId, ProjectOperationKind.CLEANUP)) {
                val exclusion = object : CacheCleanupExclusion {
                    override val projectId = plan.projectId
                    override fun snapshot() = runBlocking { referenceSource.read(projectId).protections }
                    override fun close() = Unit // The repository scope owns the actual exclusion.
                }
                val collector = CacheCollector(FileCleanupJournalStore(journalRoot.toPath()))
                collector.execute(repository.directory(projectId).toPath(), plan, exclusion, cancellation)
            }
        }

    private suspend fun inventory(projectId: String, references: CacheReferenceData): CacheInventory {
        val root = repository.directory(projectId).toPath().toAbsolutePath().normalize()
        val descriptors = LinkedHashMap<String, CacheArtifactDescriptor>()
        val ambiguousPaths = HashSet<String>()
        references.stageManifests.forEach { manifest ->
            require(manifest.binding.projectId == projectId)
            val category = when (manifest.identity.stage) {
                PipelineStage.FEATURES -> CacheArtifactCategory.FEATURE_CACHE
                PipelineStage.DENSE_DEPTH -> CacheArtifactCategory.DEPTH_CHECKPOINT
                PipelineStage.EXPORT -> CacheArtifactCategory.RESULT
                else -> CacheArtifactCategory.INTERMEDIATE
            }
            val state = if (manifest.binding.projectRevision == references.projectRevision)
                CacheArtifactState.REUSABLE else CacheArtifactState.STALE
            manifest.artifacts.forEach { artifact ->
                val next = CacheArtifactDescriptor(artifact.relativePath, category, state,
                    artifact.stageIdentityHash.hex, manifest.binding.projectRevision, artifact.byteSize,
                    artifact.contentHash.hex)
                if (next.relativePath !in ambiguousPaths) {
                    val merged = CacheDescriptorBindings.merge(descriptors[next.relativePath], next)
                    if (merged == null) {
                        // Different bytes at a reused path are ambiguous without matching the file.
                        // Preserve that file as UNKNOWN while allowing unrelated verified caches.
                        descriptors.remove(next.relativePath)
                        ambiguousPaths += next.relativePath
                    } else descriptors[next.relativePath] = merged
                }
            }
        }
        repository.dao.photos(projectId).forEach { photo ->
            val path = CachePathPolicy.fromRoot(root, repository.photoFile(photo).toPath())
            descriptors[path] = CacheArtifactDescriptor(path, CacheArtifactCategory.ORIGINAL,
                CacheArtifactState.CURRENT, photo.sha256, expectedBytes = photo.bytes,
                expectedSha256 = photo.sha256, preserve = true)
        }
        references.publishedSets.forEach { set ->
            set.manifest.entries.filter { it.presence == ArtifactPresence.PRESENT }.forEach { entry ->
                val path = CachePathPolicy.fromRoot(root, File(set.directory, entry.declaration.relativePath).toPath())
                descriptors[path] = CacheArtifactDescriptor(path, CacheArtifactCategory.RESULT,
                    CacheArtifactState.CURRENT, set.manifest.setId, set.manifest.identity.projectRevision,
                    entry.bytes, entry.sha256, preserve = true)
            }
        }
        val manifest = CacheArtifactManifest(1, "references-${references.protections.generation}", projectId,
            references.projectRevision, "integrated-c8-c23-v1", descriptors.values.toList())
        return CacheInventoryBuilder().build(root, manifest, references.protections)
    }
}

/** A reused identical cache can be recorded for multiple revisions without becoming corrupt. */
object CacheDescriptorBindings {
    fun merge(previous: CacheArtifactDescriptor?, next: CacheArtifactDescriptor): CacheArtifactDescriptor? {
        if (previous == null || previous == next) return next
        require(previous.relativePath == next.relativePath)
        if (previous.category != next.category || previous.expectedBytes != next.expectedBytes ||
            previous.expectedSha256 == null || previous.expectedSha256 != next.expectedSha256) return null
        if (previous.revision == next.revision && previous.identity != next.identity) return null
        return if ((previous.revision ?: -1) >= (next.revision ?: -1)) previous else next
    }
}
