package com.realityforge.scanner.ui.navigation

import java.util.UUID

enum class Destination { LIBRARY, REVIEW, CAPTURE, PROCESSING, STUDIO, EXPORT, SETTINGS, ONBOARDING }
data class AppRoute(val destination: Destination, val projectId: String? = null) {
    init { require(destination !in projectDestinations || !projectId.isNullOrBlank()) }
    fun back(): AppRoute = when (destination) {
        Destination.LIBRARY -> this
        Destination.REVIEW -> AppRoute(Destination.LIBRARY)
        Destination.SETTINGS, Destination.ONBOARDING -> if(projectId==null) AppRoute(Destination.LIBRARY) else AppRoute(Destination.REVIEW,projectId)
        else -> AppRoute(Destination.REVIEW, projectId)
    }
    fun encode(): String = destination.name + ":" + projectId.orEmpty()
    companion object {
        private val projectDestinations = setOf(Destination.REVIEW, Destination.CAPTURE, Destination.PROCESSING, Destination.STUDIO, Destination.EXPORT)
        fun restore(value: String?): AppRoute = runCatching {
            val parts = requireNotNull(value).split(':', limit = 2)
            AppRoute(Destination.valueOf(parts[0]), parts.getOrNull(1)?.takeIf(String::isNotBlank))
        }.getOrDefault(AppRoute(Destination.LIBRARY))
    }
}

enum class PendingKind { IMPORT, CALIBRATE, CAMERA, EXPORT, CAPTURE_ARCHIVE }
/** A picker cannot choose a new destination from mutable UI selection. */
data class PendingRequest(val requestId: String, val kind: PendingKind, val projectId: String,
    val revision: Long, val artifactKey: String? = null, val extension: String? = null) {
    init {
        require(requestId.isNotBlank() && projectId.isNotBlank() && revision >= 0)
        require(!projectId.contains('\n') && !requestId.contains('\n'))
        require(kind != PendingKind.EXPORT || (!artifactKey.isNullOrBlank() && extension in exportExtensions))
        require(listOf(artifactKey, extension).none { it?.contains('\n') == true })
    }
    fun accepts(selectedId: String?, currentRevision: Long?, currentArtifact: String?, busy: Boolean): Boolean =
        !busy && selectedId == projectId && currentRevision == revision &&
            (kind != PendingKind.EXPORT || currentArtifact == artifactKey)
    fun encode(): String = listOf(requestId, kind.name, projectId, revision.toString(), artifactKey.orEmpty(), extension.orEmpty()).joinToString("\n")
    companion object {
        val exportExtensions = setOf("glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply", "textured.glb", "texture.png", "json")
        fun create(kind: PendingKind, projectId: String, revision: Long, artifactKey: String? = null, extension: String? = null) =
            PendingRequest(UUID.randomUUID().toString(), kind, projectId, revision, artifactKey, extension)
        fun restore(value: String?): PendingRequest? = runCatching {
            val parts = requireNotNull(value).split('\n')
            require(parts.size == 6)
            PendingRequest(parts[0], PendingKind.valueOf(parts[1]), parts[2], parts[3].toLong(), parts[4].ifEmpty { null }, parts[5].ifEmpty { null })
        }.getOrNull()
    }
}
