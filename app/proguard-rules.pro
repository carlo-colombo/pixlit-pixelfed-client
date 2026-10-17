# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the Proguard
# files in build.gradle.

# Readable stack traces for sideloaded release builds: keep the source
# attributes and rename the file so obfuscated frames map through mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Retrofit and OkHttp/Okio ship their own consumer ProGuard rules, already applied
# and visible in build/outputs/mapping/<variant>/configuration.txt, so this file
# no longer duplicates their -keep/-dontwarn rules.

# Keep Retrofit / Gson data models and annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Gson rules
-keepattributes Signature
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson specific rules to fix IllegalStateException with TypeToken in release builds
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.ParameterizedType

# Keep all data models used with Gson/Retrofit
-keep class ovh.litapp.pixlit.data.api.** { *; }

# CachedTagSearch is a private type in data.repository deserialised reflectively through
# TypeToken<Map<String, CachedTagSearch>> with no @SerializedName, so R8 must not rename or
# strip it; otherwise the tag-search cache silently parses to an empty map in release builds.
-keep class ovh.litapp.pixlit.data.repository.CachedTagSearch { *; }

# Coil ships no consumer rules, so this targeted suppression is still required.
-dontwarn io.coil-kt.**
