# RootProvider ProGuard Rules
# Keep all JSON parsing classes
-keep class org.json.** { *; }

# Keep Shizuku
-keep class rikka.shizuku.** { *; }

# Keep our app classes
-keep class com.rootprovider.** { *; }
