# Rabeto R8 rules.

# The WebView JavaScript bridge is reached only by name from JS.
-keepclassmembers class com.rabeto.app.ui.RabetoBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.rabeto.app.ui.RabetoBridge { *; }

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Nearby / Play Services.
-dontwarn com.google.android.gms.**

# Keep crypto provider names resolvable.
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Strip verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
