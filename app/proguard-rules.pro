# Keep DNS/protocol model classes intact for reflection-free runtime use.
-keep class com.skipad.core.dns.** { *; }
-keep class com.skipad.core.net.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
