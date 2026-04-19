# ProGuard rules for findeck Android app

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
-keep,includedescriptorclasses class app.findeck.mobile.data.model.**$$serializer { *; }
-keepclassmembers class app.findeck.mobile.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.findeck.mobile.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
