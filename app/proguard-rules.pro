# JNI uses exported Java_com_realityforge_scanner_engine_NativeCore_* symbols.
# Keep only this bridge and its native signatures; the reconstruction/UI remains optimizable.
-keep,allowoptimization class com.realityforge.scanner.engine.NativeCore {
    public static final com.realityforge.scanner.engine.NativeCore INSTANCE;
    native <methods>;
}
# OpenCV calls native methods by Java name. Retain bridge names and native method
# signatures instead of retaining every field and ordinary method in the package.
-keepclasseswithmembernames,includedescriptorclasses class org.opencv.** {
    native <methods>;
}
# JNI constructs these wrappers and accesses their native-address fields.
-keepclassmembers class org.opencv.core.Mat { long nativeObj; <init>(long); }
