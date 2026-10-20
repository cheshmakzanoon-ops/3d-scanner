package com.realityforge.scanner.engine.nativebridge

/** Stable recovery classification for JNI exceptions; VM allocation failures retain their type. */
enum class NativeBoundaryFailure {
    INVALID_INPUT, RESOURCE_EXHAUSTED, CANCELLED, LIBRARY_UNAVAILABLE, NATIVE_FAILURE;
    companion object {
        fun from(failure: Throwable): NativeBoundaryFailure = when (failure) {
            is java.util.concurrent.CancellationException -> CANCELLED
            is OutOfMemoryError, is NativeResourceExhaustedException -> RESOURCE_EXHAUSTED
            is LinkageError -> LIBRARY_UNAVAILABLE
            is IllegalArgumentException -> INVALID_INPUT
            else -> NATIVE_FAILURE
        }
    }
}
