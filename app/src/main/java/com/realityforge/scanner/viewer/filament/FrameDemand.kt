package com.realityforge.scanner.viewer.filament

/** Main-thread-only demand for the static inspection renderer, not an animation clock. */
internal class FrameDemand {
    var pending = true
        private set

    fun invalidate() { pending = true }

    /** A declined beginFrame must not consume the newest display state. */
    fun draw(submit: () -> Boolean) {
        if (!pending) return
        // Clear first so an invalidation during submission cannot be lost.
        pending = false
        try {
            if (!submit()) pending = true
        } catch (failure: Throwable) {
            pending = true
            throw failure
        }
    }
}
