# KAI — keep API models if minify is enabled later
-keepclassmembers class com.kai.assistant.api.** { *; }

# MediaPipe LLM Inference uses JNI and reflection into task classes.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
