package com.realityforge.scanner.agents.a020

import com.realityforge.scanner.data.cachegc.CacheArtifactCategory
import com.realityforge.scanner.data.cachegc.CacheArtifactDescriptor
import com.realityforge.scanner.data.cachegc.CacheArtifactManifest
import com.realityforge.scanner.data.cachegc.CacheArtifactState
import com.realityforge.scanner.data.cachegc.CacheCleanupExclusion
import com.realityforge.scanner.data.cachegc.CacheProtectionSnapshot
import com.realityforge.scanner.data.cachegc.CacheRetentionPlanner
import com.realityforge.scanner.data.cachegc.CacheRetentionPolicy
import com.realityforge.scanner.data.cachegc.CleanupJournal
import com.realityforge.scanner.data.cachegc.CleanupJournalStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

internal const val PROJECT = "project-020"
internal const val NOW = 20L * 24L * 60L * 60L * 1000L

internal fun planner(
    keepDepth: Int = 1,
    keepResults: Int = 1,
) = CacheRetentionPlanner(
    CacheRetentionPolicy(
        staleFeatureGraceMillis = 0,
        staleDepthGraceMillis = 0,
        staleIntermediateGraceMillis = 0,
        obsoleteResultGraceMillis = 0,
        abandonedTemporaryGraceMillis = 0,
        keepRecentDepthIdentities = keepDepth,
        keepRecentResultIdentities = keepResults,
    ),
)

internal fun manifest(vararg descriptors: CacheArtifactDescriptor) = CacheArtifactManifest(
    schemaVersion = 1,
    manifestId = "manifest-020",
    projectId = PROJECT,
    projectRevision = 3,
    engineVersion = "agent-020-test",
    artifacts = descriptors.toList(),
)

internal fun descriptor(
    path: String,
    category: CacheArtifactCategory,
    state: CacheArtifactState,
    bytes: ByteArray,
    identity: String? = null,
    revision: Long? = null,
    includeHash: Boolean = true,
) = CacheArtifactDescriptor(
    relativePath = path,
    category = category,
    state = state,
    identity = identity,
    revision = revision,
    expectedBytes = bytes.size.toLong(),
    expectedSha256 = if (includeHash) sha256(bytes) else null,
)

internal fun write(root: Path, relative: String, value: String): ByteArray {
    val bytes = value.toByteArray()
    val target = root.resolve(relative)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return bytes
}

internal fun setAge(root: Path, relative: String, days: Int) {
    Files.setLastModifiedTime(
        root.resolve(relative),
        FileTime.fromMillis(NOW - days * 24L * 60L * 60L * 1000L),
    )
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal inline fun withProject(block: (Path, Path) -> Unit) {
    val base = Files.createTempDirectory("rf-agent-020")
    val root = Files.createDirectories(base.resolve("project"))
    try {
        block(base, root)
    } finally {
        base.toFile().deleteRecursively()
    }
}

internal class FixedExclusion(
    private val protection: CacheProtectionSnapshot,
) : CacheCleanupExclusion {
    override val projectId: String = protection.projectId
    override fun snapshot(): CacheProtectionSnapshot = protection
    override fun close() = Unit
}

internal class FailFirstSaveJournalStore : CleanupJournalStore {
    override val rootDirectory: Path? = null
    private var fail = true
    private var journal: CleanupJournal? = null

    override fun load(planId: String): CleanupJournal? = journal

    override fun save(journal: CleanupJournal) {
        if (fail) {
            fail = false
            throw java.io.IOException("injected journal failure")
        }
        this.journal = journal
    }

    override fun clear(planId: String) {
        journal = null
    }
}
