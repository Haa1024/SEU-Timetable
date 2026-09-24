# kotlinx.serialization 在 R8 下需要保留序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留本工程所有 @Serializable 数据类的序列化器（反射查找用）
-keep,includedescriptorclasses class com.seu.timetable.**$$serializer { *; }
-keepclassmembers class com.seu.timetable.** {
    *** Companion;
}
-keepclasseswithmembers class com.seu.timetable.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
