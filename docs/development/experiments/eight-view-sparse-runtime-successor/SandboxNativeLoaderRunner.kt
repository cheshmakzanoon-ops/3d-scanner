package com.realityforge.scanner.hostintegration

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/** Original native-owning classes in ONE sandbox; no parent preload or fake loader. */
class SandboxNativeLoaderRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method)).apply {
            doNotInstrumentPackage("org.opencv.")
            doNotInstrumentClass("com.realityforge.scanner.engine.NativeCore")
            doNotInstrumentClass("com.realityforge.scanner.export.NativeAssetExports")
            // Deliberately NO doNotAcquire*, loader shadows, logging shadows or Utils bridge.
        }.build()
}
