# Naver Login SDK 5.12.0 resolves services through Kotlin reflection. R8 full
# mode can rewrite the service locator's generic types and crash at startup.
-keep class com.navercorp.nid.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod

# Kakao's native renderer resolves these classes and members through JNI.
-keep class com.kakao.vectormap.** { *; }
-keep interface com.kakao.vectormap.**
