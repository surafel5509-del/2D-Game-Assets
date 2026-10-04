# Lumen2D engine-android — R8/ProGuard rules for apps that embed the engine.
#
# The engine is reflection-free by design: node types come from an explicit registry, the scene
# format is JSON and LumenScript is interpreted, so nothing has to be kept for lookups by name.
# Only the Android backend classes need to survive shrinking.

-keep class dev.lumen2d.android.** { *; }

# Keep enum values that are written to scene files and input maps.
-keepclassmembers enum dev.lumen2d.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Optional APIs referenced from the audio/vibration backends on newer platforms.
-dontwarn android.media.AudioAttributes
-dontwarn android.os.VibrationEffect
