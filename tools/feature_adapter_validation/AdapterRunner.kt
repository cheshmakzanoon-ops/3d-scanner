package hostadapter

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

class AdapterRunner(testClass:Class<*>):RobolectricTestRunner(testClass) {
 override fun createClassLoaderConfig(method:FrameworkMethod):InstrumentationConfiguration =
  InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method)).apply {
   // Real OpenCV Java/JNI stays in one parent loader. Only android.Utils is instrumented.
   for(p in listOf("org.opencv.core.","org.opencv.features2d.","org.opencv.imgproc.","org.opencv.utils.")) doNotAcquirePackage(p)
   doNotAcquireClass("hostadapter.HostOpenCvLoader")
  }.build()
}
