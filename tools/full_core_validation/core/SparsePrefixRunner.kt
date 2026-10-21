package com.realityforge.scanner.hostintegration

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

class SparsePrefixRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method)).apply {
            for (name in listOf("org.opencv.core.", "org.opencv.features2d.", "org.opencv.imgproc.", "org.opencv.utils."))
                doNotInstrumentPackage(name)
            doNotInstrumentClass("org.opencv.android.OpenCVLoader")
            doNotInstrumentClass("org.opencv.android.StaticHelper")
            doNotInstrumentClass("com.realityforge.scanner.engine.NativeCore")
            addInstrumentedClass("org.opencv.android.Utils")
            // All acquired by this sandbox. Never parent preload native-owning classes.
        }.build()
}
