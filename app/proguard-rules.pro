# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.itantra.schema.** { *; }
-keep class com.itantra.mesh.** { *; }
