# ProGuard rules for CodexRemote Android app

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep app data models for serialization
-keep,includedescriptorclasses class dev.codexremote.android.data.model.**$$serializer { *; }
-keepclassmembers class dev.codexremote.android.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class dev.codexremote.android.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
