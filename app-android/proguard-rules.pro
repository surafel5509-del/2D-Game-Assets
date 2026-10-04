# Lumen2D Studio — release shrinker rules.
#
# The engine is reflection-free: node types live in an explicit registry, scenes are JSON and
# LumenScript is interpreted, so the shrinker cannot break gameplay code by renaming a class.
# These rules cover the framework entry points that are called by name only.

-keep class dev.lumen2d.studio.MainActivity { *; }
-keep class dev.lumen2d.studio.StudioActivity { *; }
-keep class dev.lumen2d.android.** { *; }

# Custom views are inflated by name when a layout references them.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Keep enum values used by serialised input maps / scene files.
-keepclassmembers enum dev.lumen2d.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Assets are read through AssetManager, never by reflection: nothing else to keep.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
