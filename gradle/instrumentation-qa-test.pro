# QA test APK only. Error-prone's compile-time annotation signature uses a JDK
# compiler enum absent from Android. Exact context remains independently checked
# by instrumentation_abi.py; this does not permit missing AndroidX/app/Kotlin APIs.
-dontwarn javax.lang.model.element.Modifier
