# Lusail Stadium Matchday Companion — ProGuard Rules

# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes used for serialization
-keep class com.lusail.stadium.models.** { *; }
-keep class com.lusail.stadium.network.** { *; }

# Keep JavaScript interface methods
-keepclassmembers class com.lusail.stadium.bridge.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
