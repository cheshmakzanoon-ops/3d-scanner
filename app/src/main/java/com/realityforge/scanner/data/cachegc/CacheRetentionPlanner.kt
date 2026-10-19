package com.realityforge.scanner.data.cachegc

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.EnumMap

private const val HOUR_MILLIS = 60L * 60L * 1000L
private const val DAY_MILLIS = 24L * HOUR_MILLIS

data class CacheRetentionPolicy(
    val staleFeatureGraceMillis: Long = DAY_MILLIS,
    val staleDepthGraceMillis: Long = DAY_MILLIS,
    val staleIntermediateGraceMillis: Long = DAY_MILLIS,
    val obsoleteResultGraceMillis: Long = 7L * DAY_MILLIS,
    val abandonedTemporaryGraceMillis: Long = HOUR_MILLIS,
    val keepRecentDepthIdentities: Int = 1,
    val keepRecentResultIdentities: Int = 1,
    val maxDeleteEntries: Int = 10_000,
    val maxDeleteBytes: Long = 4L * 1024L * 1024L * 1024L,
) {
    init {
        listOf(
            staleFeatureGraceMillis,
            staleDepthGraceMillis,
            staleIntermediateGraceMillis,
            obsoleteResultGraceMillis,
            abandonedTemporaryGraceMillis,
        ).forEach { require(it >= 0L) { "Retention grace periods cannot be negative." } }
        require(keepRecentDepthIdentities in 0..100)
        require(keepRecentResultIdentities in 0..100)
        require(maxDeleteEntries in 1..CacheArtifactManifest.MAX_ARTIFACTS)
        require(maxDeleteBytes > 0L)
    }
}

enum class CleanupAction {
    KEEP,
    DELETE,
}

enum class CleanupReason {
    KEEP_ORIGINAL,
    KEEP_UNKNOWN,
    KEEP_UNSAFE_TYPE,
    KEEP_MANIFEST_MISMATCH,
    KEEP_MANIFEST_PINNED,
    KEEP_PROTECTED_REFERENCE,
    KEEP_CURRENT_OR_REUSABLE,
    KEEP_UNKNOWN_STATE,
    KEEP_RECENT_IDENTITY,
    KEEP_GRACE_PERIOD,
    KEEP_PLAN_LIMIT,
    KEEP_INVENTORY_INCOMPLETE,
    DELETE_STALE_FEATURE_CACHE,
    DELETE_STALE_DEPTH_CHECKPOINT,
    DELETE_STALE_INTERMEDIATE,
    DELETE_OBSOLETE_RESULT,
    DELETE_ABANDONED_TEMPORARY,
}

data class CleanupDecision(
    val relativePath: String,
    val category: CacheArtifactCategory,
    val action: CleanupAction,
    val reason: CleanupReason,
    val bytes: Long,
    val protections: Set<CacheProtectionReason>,
)

data class CleanupTarget(
    val relativePath: String,
    val category: CacheArtifactCategory,
    val identity: String?,
    val reason: CleanupReason,
    val expectedSha256: String?,
    val fingerprint: CacheFileFingerprint,
) {
    init {
        CachePathPolicy.requireCanonical(relativePath)
        require(reason.name.startsWith("DELETE_"))
    }
}

data class CleanupPlan(
    val planId: String,
    val projectId: String,
    val projectRevision: Long,
    val manifestId: String,
    val rootIdentity: String,
    val referenceGeneration: Long,
    val createdAtMillis: Long,
    val targets: List<CleanupTarget>,
    val decisions: List<CleanupDecision>,
    val reclaimableBytes: Long,
    val reclaimableBytesByCategory: Map<CacheArtifactCategory, Long>,
    val inventoryIssues: List<InventoryIssue>,
) {
    init {
        require(CacheArtifactDescriptor.SHA256.matches(planId))
        require(CacheArtifactDescriptor.SHA256.matches(rootIdentity))
        require(projectRevision >= 0L && referenceGeneration >= 0L && createdAtMillis >= 0L)
        require(targets.map { it.relativePath }.toSet().size == targets.size)
        require(reclaimableBytes >= 0L)
    }
}

/** Deterministic policy engine; it performs no filesystem mutation. */
class CacheRetentionPlanner(
    private val policy: CacheRetentionPolicy = CacheRetentionPolicy(),
) {
    fun plan(inventory: CacheInventory, nowMillis: Long = System.currentTimeMillis()): CleanupPlan {
        require(nowMillis >= inventory.scannedAtMillis) {
            "Cleanup time cannot precede the inventory snapshot."
        }

        val retainedDepthIdentities = recentIdentities(
            inventory.entries,
            CacheArtifactCategory.DEPTH_CHECKPOINT,
            policy.keepRecentDepthIdentities,
        )
        val retainedResultIdentities = recentIdentities(
            inventory.entries,
            CacheArtifactCategory.RESULT,
            policy.keepRecentResultIdentities,
        )

        val inventoryIncomplete = inventory.issues.any { issue ->
            issue.code in setOf(
                InventoryIssueCode.DIRECTORY_DEPTH_LIMIT,
                InventoryIssueCode.FILE_COUNT_LIMIT,
                InventoryIssueCode.BYTE_ACCOUNTING_OVERFLOW,
                InventoryIssueCode.READ_FAILURE,
            )
        }
        val preliminary = inventory.entries.map { entry ->
            val decision = decide(entry, nowMillis, retainedDepthIdentities, retainedResultIdentities)
            if (inventoryIncomplete && decision.action == CleanupAction.DELETE) {
                decision.copy(action = CleanupAction.KEEP, reason = CleanupReason.KEEP_INVENTORY_INCOMPLETE)
            } else {
                decision
            }
        }
        val entriesByPath = inventory.entries.associateBy { it.relativePath }
        val deletable = preliminary.filter { it.action == CleanupAction.DELETE }
            .sortedWith(compareBy<CleanupDecision>(
                { deletionPriority(it.category) },
                { requireNotNull(entriesByPath[it.relativePath]).lastModifiedMillis },
                { it.relativePath },
            ))

        val admitted = LinkedHashSet<String>()
        var admittedBytes = 0L
        for (decision in deletable) {
            if (admitted.size >= policy.maxDeleteEntries) break
            if (decision.bytes > policy.maxDeleteBytes - admittedBytes) continue
            admitted += decision.relativePath
            admittedBytes += decision.bytes
        }

        val finalDecisions = preliminary.map { decision ->
            if (decision.action == CleanupAction.DELETE && decision.relativePath !in admitted) {
                decision.copy(action = CleanupAction.KEEP, reason = CleanupReason.KEEP_PLAN_LIMIT)
            } else {
                decision
            }
        }.sortedBy { it.relativePath }

        val targets = finalDecisions.filter { it.action == CleanupAction.DELETE }.map { decision ->
            val entry = requireNotNull(entriesByPath[decision.relativePath])
            CleanupTarget(
                relativePath = entry.relativePath,
                category = entry.category,
                identity = entry.identity,
                reason = decision.reason,
                expectedSha256 = entry.expectedSha256,
                fingerprint = entry.fingerprint,
            )
        }

        val byCategory = EnumMap<CacheArtifactCategory, Long>(CacheArtifactCategory::class.java)
        targets.forEach { target ->
            val current = byCategory[target.category] ?: 0L
            byCategory[target.category] = safeAdd(current, target.fingerprint.bytes)
        }
        val planId = planId(inventory, nowMillis, targets)
        return CleanupPlan(
            planId = planId,
            projectId = inventory.projectId,
            projectRevision = inventory.projectRevision,
            manifestId = inventory.manifestId,
            rootIdentity = inventory.rootIdentity,
            referenceGeneration = inventory.referenceGeneration,
            createdAtMillis = nowMillis,
            targets = targets,
            decisions = finalDecisions,
            reclaimableBytes = targets.fold(0L) { total, target -> safeAdd(total, target.fingerprint.bytes) },
            reclaimableBytesByCategory = CacheArtifactCategory.entries.associateWith { byCategory[it] ?: 0L },
            inventoryIssues = inventory.issues,
        )
    }

    private fun decide(
        entry: CacheInventoryEntry,
        nowMillis: Long,
        retainedDepthIdentities: Set<String>,
        retainedResultIdentities: Set<String>,
    ): CleanupDecision {
        fun keep(reason: CleanupReason) = CleanupDecision(
            entry.relativePath,
            entry.category,
            CleanupAction.KEEP,
            reason,
            entry.bytes,
            entry.protections,
        )
        fun delete(reason: CleanupReason) = CleanupDecision(
            entry.relativePath,
            entry.category,
            CleanupAction.DELETE,
            reason,
            entry.bytes,
            entry.protections,
        )

        if (entry.protections.isNotEmpty()) return keep(CleanupReason.KEEP_PROTECTED_REFERENCE)
        if (entry.type != InventoryFileType.REGULAR_FILE) return keep(CleanupReason.KEEP_UNSAFE_TYPE)
        if (entry.manifestMatch == ManifestMatch.UNDECLARED || entry.category == CacheArtifactCategory.UNKNOWN) {
            return keep(CleanupReason.KEEP_UNKNOWN)
        }
        if (entry.manifestMatch != ManifestMatch.MATCHED) return keep(CleanupReason.KEEP_MANIFEST_MISMATCH)
        if (entry.category == CacheArtifactCategory.ORIGINAL) return keep(CleanupReason.KEEP_ORIGINAL)
        if (entry.preserveByManifest) return keep(CleanupReason.KEEP_MANIFEST_PINNED)
        if (entry.state == CacheArtifactState.CURRENT || entry.state == CacheArtifactState.REUSABLE) {
            return keep(CleanupReason.KEEP_CURRENT_OR_REUSABLE)
        }
        if (entry.state == CacheArtifactState.UNKNOWN) return keep(CleanupReason.KEEP_UNKNOWN_STATE)
        if (entry.category == CacheArtifactCategory.DEPTH_CHECKPOINT && entry.identity in retainedDepthIdentities) {
            return keep(CleanupReason.KEEP_RECENT_IDENTITY)
        }
        if (entry.category == CacheArtifactCategory.RESULT && entry.identity in retainedResultIdentities) {
            return keep(CleanupReason.KEEP_RECENT_IDENTITY)
        }

        val age = (nowMillis - entry.lastModifiedMillis).coerceAtLeast(0L)
        val grace = when (entry.category) {
            CacheArtifactCategory.FEATURE_CACHE -> policy.staleFeatureGraceMillis
            CacheArtifactCategory.DEPTH_CHECKPOINT -> policy.staleDepthGraceMillis
            CacheArtifactCategory.INTERMEDIATE -> policy.staleIntermediateGraceMillis
            CacheArtifactCategory.RESULT -> policy.obsoleteResultGraceMillis
            CacheArtifactCategory.TEMPORARY -> policy.abandonedTemporaryGraceMillis
            CacheArtifactCategory.ORIGINAL,
            CacheArtifactCategory.UNKNOWN,
            -> Long.MAX_VALUE
        }
        if (age < grace) return keep(CleanupReason.KEEP_GRACE_PERIOD)

        return when (entry.category) {
            CacheArtifactCategory.FEATURE_CACHE -> delete(CleanupReason.DELETE_STALE_FEATURE_CACHE)
            CacheArtifactCategory.DEPTH_CHECKPOINT -> delete(CleanupReason.DELETE_STALE_DEPTH_CHECKPOINT)
            CacheArtifactCategory.INTERMEDIATE -> delete(CleanupReason.DELETE_STALE_INTERMEDIATE)
            CacheArtifactCategory.RESULT -> delete(CleanupReason.DELETE_OBSOLETE_RESULT)
            CacheArtifactCategory.TEMPORARY -> delete(CleanupReason.DELETE_ABANDONED_TEMPORARY)
            CacheArtifactCategory.ORIGINAL -> keep(CleanupReason.KEEP_ORIGINAL)
            CacheArtifactCategory.UNKNOWN -> keep(CleanupReason.KEEP_UNKNOWN)
        }
    }

    private fun recentIdentities(
        entries: List<CacheInventoryEntry>,
        category: CacheArtifactCategory,
        count: Int,
    ): Set<String> {
        if (count == 0) return emptySet()
        return entries.asSequence()
            .filter {
                it.category == category &&
                    it.identity != null &&
                    it.state in setOf(CacheArtifactState.STALE, CacheArtifactState.OBSOLETE)
            }
            .groupBy { requireNotNull(it.identity) }
            .mapValues { (_, group) -> group.maxOf { it.lastModifiedMillis } }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(count)
            .mapTo(LinkedHashSet()) { it.key }
    }

    private fun deletionPriority(category: CacheArtifactCategory): Int = when (category) {
        CacheArtifactCategory.TEMPORARY -> 0
        CacheArtifactCategory.FEATURE_CACHE -> 1
        CacheArtifactCategory.DEPTH_CHECKPOINT -> 2
        CacheArtifactCategory.INTERMEDIATE -> 3
        CacheArtifactCategory.RESULT -> 4
        CacheArtifactCategory.ORIGINAL,
        CacheArtifactCategory.UNKNOWN,
        -> 5
    }

    private fun planId(
        inventory: CacheInventory,
        nowMillis: Long,
        targets: List<CleanupTarget>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        add("realityforge-cache-cleanup-plan-v1")
        add(inventory.projectId)
        add(inventory.projectRevision.toString())
        add(inventory.manifestId)
        add(inventory.rootIdentity)
        add(inventory.referenceGeneration.toString())
        add(nowMillis.toString())
        targets.sortedBy { it.relativePath }.forEach { target ->
            add(target.relativePath)
            add(target.category.name)
            add(target.identity ?: "")
            add(target.reason.name)
            add(target.expectedSha256 ?: "")
            add(target.fingerprint.bytes.toString())
            add(target.fingerprint.lastModifiedMillis.toString())
            add(target.fingerprint.fileKeySha256 ?: "")
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
}
