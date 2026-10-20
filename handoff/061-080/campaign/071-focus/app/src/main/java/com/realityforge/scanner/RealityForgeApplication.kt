package com.realityforge.scanner

import android.app.Application
import com.realityforge.scanner.application.ScannerCompositionRoot

class RealityForgeApplication : Application() {
    /** Process-wide construction only; operation lifetime is owned by the application layer. */
    val compositionRoot by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScannerCompositionRoot.create(this)
    }

    // Compatibility accessors for independently owned screens/services during staged integration.
    val database get() = compositionRoot.database
    val repository get() = compositionRoot.repository
}
