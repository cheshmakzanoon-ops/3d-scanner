package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.engine.NativeCore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pull owned native work counts at most ten times a second, only while this call is active. */
internal suspend fun <T> withNativeStageProgress(
    token: Long,
    onProgress: suspend (String, Int, Int) -> Unit,
    block: () -> T,
): T = coroutineScope {
    require(token > 0) { "Missing scoped native operation token" }
    ensureActive()
    val finished = AtomicBoolean(false)
    // UNDISPATCHED installs the cancellation finally before the synchronous JNI call can block.
    val reporter = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            var lastSequence = -1L
            val labels = arrayOf("Sparse geometry", "Bundle adjustment", "Dense depth", "Depth consistency",
                "Surface fusion", "Photographic texture", "Depth checkpoint")
            while (isActive) {
                delay(100)
                val value = NativeCore.pollNativeProgress(token)
                if (value.size != 7 || value[0] != 1L || value[6] == lastSequence || value[6] < 0) continue
                val stage = value[1].takeIf { it in 0L..6L }?.toInt() ?: continue
                val completed = value[4]; val total = value[5]
                if (completed !in 0L..Int.MAX_VALUE.toLong() || total !in -1L..Int.MAX_VALUE.toLong() ||
                    (total >= 0 && completed > total)) continue
                lastSequence = value[6]
                onProgress(labels[stage], completed.toInt(), if (total < 0) 0 else total.toInt())
            }
        } finally {
            if (!finished.get()) NativeCore.cancelReconstruction(token)
        }
    }
    try {
        block()
    } finally {
        finished.set(true)
        // No reporter callback may escape into a subsequent pipeline stage, including cancellation.
        withContext(NonCancellable) { reporter.cancelAndJoin() }
    }
}
