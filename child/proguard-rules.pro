-keep class com.mylittleworld.child.web.LearningBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Tink (via androidx.security-crypto) references compile-time-only errorprone annotations.
-dontwarn com.google.errorprone.annotations.**
