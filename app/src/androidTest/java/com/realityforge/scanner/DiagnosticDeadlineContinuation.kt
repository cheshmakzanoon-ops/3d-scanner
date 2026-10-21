package com.realityforge.scanner

/** Typed test deadline, never a production timeout or a generic assertion classifier. */
internal class OperationDeadlineExceeded(val deadlineMillis: Long, detail: String) : AssertionError(detail)

/** Opt-in diagnostic only. A missed normal deadline ALWAYS remains the final JUnit failure.
 * 180s + 600s bounds reconstruction waiting, not import, functional checks or cleanup time.
 */
internal class DiagnosticDeadlineContinuation private constructor(private val enabled: Boolean) {
    companion object {
        const val ARGUMENT = "rfDiagnosticContinuation"
        const val NORMAL_WAIT_MILLIS = 180_000L
        const val ADDITIONAL_WAIT_MILLIS = 600_000L
        fun parse(value: String?): DiagnosticDeadlineContinuation = when (value) {
            null, "false" -> DiagnosticDeadlineContinuation(false)
            "true" -> DiagnosticDeadlineContinuation(true)
            else -> throw IllegalArgumentException("$ARGUMENT must be true or false")
        }
    }

    suspend fun run(initialWait: suspend () -> Unit, continuedWait: suspend (Long) -> Unit, functionalChecks: suspend () -> Unit,
        event: (String) -> Unit = {}) {
        val original = try {
            initialWait()
            null
        } catch (deadline: OperationDeadlineExceeded) {
            if (!enabled || deadline.deadlineMillis != NORMAL_WAIT_MILLIS) throw deadline
            deadline
        }
        if (original == null) {
            functionalChecks()
            return
        }
        try {
            event("DIAGNOSTIC_DEADLINE_MISSED")
            continuedWait(ADDITIONAL_WAIT_MILLIS)
            functionalChecks()
            // The caller supplies ALL unchanged geometry/reopen/export/hash/GPU assertions.
            event("DIAGNOSTIC_FUNCTIONAL_COMPLETION")
        } catch (later: Throwable) {
            if (later !== original) original.addSuppressed(later)
        }
        throw original
    }
}
