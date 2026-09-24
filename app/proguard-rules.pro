# Add project specific ProGuard rules here.
-dontwarn rikka.shizuku.**
-keep class rikka.shizuku.** { *; }

# Shizuku 用户服务由服务端通过反射 Class.newInstance() 创建（类名经 ComponentName 传递），
# release 混淆后必须保留该类及其公开无参构造，否则 :shell 进程会 InstantiationException 启动即崩
-keep class com.baisha.MicrosoftRewardsHelper.ShellUserService { *; }

# 离线文字识别（ML Kit）用反射加载模型，别被裁掉
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }
