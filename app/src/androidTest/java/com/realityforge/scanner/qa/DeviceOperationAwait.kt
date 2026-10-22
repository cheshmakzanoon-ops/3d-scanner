package com.realityforge.scanner.qa

import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.domain.ScannerState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals

/** Test-only synchronization; never treat idle-without-a-transfer as verified export success. */
object DeviceOperationAwait {
    suspend fun verifiedArtifactExport(model: ScannerViewModel, start: () -> Unit) =
        verifiedArtifactExport(model.scannerState, model.busy, start)

    internal suspend fun verifiedArtifactExport(states: StateFlow<ScannerState>, busy: StateFlow<Boolean>,
        start: () -> Unit) = withTimeout(30_000) {
        coroutineScope {
            check(states.value.message?.code?.startsWith("export.") != true) {
                "This helper requires a fresh export fixture, not a previous terminal export message."
            }
            // Subscribe before delivering the picker result, including very fast rejection paths.
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                states.first { state ->
                    state.message?.code?.startsWith("export.") == true ||
                        state.message?.code == "operation.stale_request"
                }
            }
            start()
            val result = terminal.await()
            busy.first { !it }
            val message = requireNotNull(result.message)
            assertEquals(message.fallbackText, "export.verified", message.code)
        }
    }
}
