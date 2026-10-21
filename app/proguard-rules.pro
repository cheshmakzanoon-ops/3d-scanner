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

# Release instrumentation shares this dependency with the target APK. R8 sees
# app callers, not AndroidJUnitRunner's separate DEX: retain its three Trace
# entry points so optimization cannot leave the runner referencing a removed class.
# Keep method bodies optimizable; do not retain the rest of androidx.tracing.
-keep,allowoptimization class androidx.tracing.Trace {
    public static void beginSection(java.lang.String);
    public static void endSection();
    public static void forceEnableAppTracing();
}
