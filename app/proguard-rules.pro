# Add project specific ProGuard rules here.
-dontwarn rikka.shizuku.**
-keep class rikka.shizuku.** { *; }

# 离线文字识别（ML Kit）用反射加载模型，别被裁掉
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }
