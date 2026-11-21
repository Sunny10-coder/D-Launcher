# Keep kotlinx.serialization generated serializers.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mylittleworld.**$$serializer { *; }
-keepclassmembers class com.mylittleworld.** {
    *** Companion;
}
-keepclasseswithmembers class com.mylittleworld.** {
    kotlinx.serialization.KSerializer serializer(...);
}
