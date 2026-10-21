package com.realityforge.scanner.domain

/** Identity of the saved presentation, including same-revision result replacement and recovery state. */
internal data class PresentationReadIdentity(
    val projectId: String,
    val revision: Long,
    val resultKey: String?,
    val resultRevision: Long?,
    val state: String,
)

/** A refresh is a read, not a new selection: it must not advance or consume a picker generation. */
internal data class PresentationReadFence(val selectionGeneration: Long, val project: PresentationReadIdentity) {
    fun isCurrent(generation: Long, selectedId: String?, current: PresentationReadIdentity?,
        operationActive: Boolean): Boolean = !operationActive && generation == selectionGeneration &&
        selectedId == project.projectId && current == project
}
