# ML Kit / CameraX keep rules
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.vision.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
