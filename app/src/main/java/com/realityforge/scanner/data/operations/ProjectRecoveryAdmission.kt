package com.realityforge.scanner.data.operations

/** A failed project recovery restricts that project, while verified saved-result reads remain available. */
object ProjectRecoveryAdmission {
    const val STATE = "RECOVERY_REQUIRED"
    const val MESSAGE = "This project has an incomplete storage recovery. Saved results remain available; retry recovery or delete this project."

    fun requireAllowed(state: String, kind: ProjectOperationKind) {
        if (state == STATE && kind !in setOf(ProjectOperationKind.READ_CURRENT_RESULT,
                ProjectOperationKind.EXPORT_RESULT, ProjectOperationKind.DELETE_PROJECT))
            throw ProjectRecoveryRequiredException()
    }
}

class ProjectRecoveryRequiredException : ProjectAccessException(ProjectRecoveryAdmission.MESSAGE)
