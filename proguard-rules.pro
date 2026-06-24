# Keep the patcher service
-keep class com.hackerai.rootspoofer.** { *; }

# Keep embedded jar classes (apktool, signer)
-keep class brut.** { *; }
-keep class com.android.** { *; }
-keep class org.apache.** { *; }

# Don't obfuscate
-dontobfuscate
-dontoptimize