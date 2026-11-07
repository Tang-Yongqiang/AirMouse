# AirMouse ProGuard/R8 规则
# 这个 App 基本不用反射，保持最小集合即可。

# 四大组件与自定义 View 由 manifest/layout 引用，R8 会自己保留；
# 这里只显式保住被反射/序列化访问的少量类。

# 友盟/崩溃上报之类没有，留空段落方便以后加

# 保留行号，方便看崩溃栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin 的 metadata 不需要，减少体积
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Material / AndroidX 常见反射入口（它们自带 consumer rules，这里只兜底）
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# 我们自己的 View 会被 XML 实例化（构造函数反射）
-keep public class com.airmouse.gyro.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
