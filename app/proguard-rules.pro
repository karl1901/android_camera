# ============ 本项目 ProGuard 规则 ============

# JNI 回调类（NativeUvc / FrameCallback 实现类）不可混淆，
# 否则 native 层按名查找方法会失败
-keep class com.wheeltech.blindcam.native.** { *; }
-keep class com.wheeltech.blindcam.domain.CameraFrame { *; }
-keepclassmembers class com.wheeltech.blindcam.** {
    native <methods>;
}

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

# DataStore
-keep class androidx.datastore.** { *; }
