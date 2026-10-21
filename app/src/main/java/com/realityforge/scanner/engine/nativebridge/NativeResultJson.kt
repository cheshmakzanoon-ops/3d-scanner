package com.realityforge.scanner.engine.nativebridge

import org.json.JSONObject

/** Native algorithm rejection may be optional; cancellation and allocation failure never are. */
object NativeResultJson {
    fun decode(encoded: String): JSONObject = JSONObject(encoded).also { result ->
        when (result.optString("errorCode")) {
            "RESOURCE_EXHAUSTED" -> throw NativeResourceExhaustedException()
            "CANCELLED" -> throw java.util.concurrent.CancellationException("Native operation cancelled.")
        }
    }
}

class NativeResourceExhaustedException : IllegalStateException("Native memory budget exhausted. Resume with a smaller workload.")
