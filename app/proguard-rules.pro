# ==============================================================================
#                       Battery App ProGuard / R8 混淆规则
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. 基础通用配置 & 调试行号保留
# ------------------------------------------------------------------------------
# 代码混淆级别与优化设置
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# 保留异常、内部类、泛型签名、注解、类封闭方法及行号信息（便于崩溃日志反解分析）
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ------------------------------------------------------------------------------
# 2. Android 原生核心组件与系统接口
# ------------------------------------------------------------------------------
# 保持四大组件及 Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# 保持所有 Native 方法声明
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持自定义 View 及 XML 中调用的标准构造函数
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
    void set*(...);
    public * get*();
}

# 保持 Parcelable 和 Serializable 序列化接口实现
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保持枚举类的 values() 与 valueOf()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保持 @Keep 注解标记的类与成员
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ------------------------------------------------------------------------------
# 3. AndroidX & Google Material Components 规则
# ------------------------------------------------------------------------------
-keep class androidx.appcompat.** { *; }
-keep class androidx.fragment.app.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.constraintlayout.** { *; }
-keep class androidx.swiperefreshlayout.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ------------------------------------------------------------------------------
# 4. ViewBinding 规则
# ------------------------------------------------------------------------------
# 保留所有自动生成的 ViewBinding 绑定类与核心访问方法
-keep class com.battery.analysis.databinding.** {
    public static ** inflate(...);
    public static ** bind(android.view.View);
    public android.view.View getRoot();
    *;
}

# ------------------------------------------------------------------------------
# 5. Shizuku API & AIDL 跨进程通信规则
# ------------------------------------------------------------------------------
# 保持 Shizuku API、Provider、Binder 及所有内部反射调用方法
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep class rikka.shizuku.Shizuku {
    public static <methods>;
    public static <fields>;
    private static <methods>;
    *;
}
-keep class rikka.shizuku.ShizukuProvider { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# ------------------------------------------------------------------------------
# 6. 项目业务层、数据模型 (Model) 与 JSON 备份恢复实体
# ------------------------------------------------------------------------------
# 保持数据实体类，避免 JSON 序列化/反序列化及属性访问混淆丢失
-keep class com.battery.analysis.model.** { *; }

# 保持自定义图表控件与 UI 视图
-keep class com.battery.analysis.ui.view.** { *; }

# 保持数据库 Helper
-keep class com.battery.analysis.db.** { *; }

# 保持数据提供者与管理服务
-keep class com.battery.analysis.provider.** { *; }
-keep class com.battery.analysis.manager.** { *; }
-keep class com.battery.analysis.viewmodel.** { *; }

# ------------------------------------------------------------------------------
# 7. Kotlin 与 kotlinx.coroutines 协程规则
# ------------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**

# ------------------------------------------------------------------------------
# 8. 忽略无害警告与提醒
# ------------------------------------------------------------------------------
-dontwarn org.json.**
-dontnote **
