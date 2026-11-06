# ---------------------------------------------------------------- 网络层
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------------------------------------------------------------- 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------------------------------------------------------------- R8 全量优化
# core 层是纯 JVM 数据类 + 手写解析，无反射/序列化/注解处理器依赖，
# 允许 R8 自由内联、去无用代码、改写可见性（旧版 -keep class ... { *; }
# 会整块冻结 core 层，白白放弃优化）。
-allowaccessmodification
-repackageclasses

# ---------------------------------------------------------------- 反射/序列化兜底
# Kotlin 反射与 Compose 内部按需保留（Compose 编译器生成的是直接调用，不用反射）
-dontwarn kotlin.reflect.**
-dontwarn androidx.compose.**
-keepclassmembers class kotlin.Metadata { public <methods>; }
