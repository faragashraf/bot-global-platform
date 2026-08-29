-keep class com.botglobal.lamma.app.data.** { *; }
-keep class com.botglobal.lamma.app.realtime.** { *; }

# Microsoft SignalR 7.0.0 serializes these wire models with Gson reflection.
# Preserve their exact JSON field names without keeping the rest of SignalR.
-keep class com.microsoft.signalr.HandshakeRequestMessage { <fields>; }
-keep class com.microsoft.signalr.HandshakeResponseMessage { <fields>; }
-keep class com.microsoft.signalr.InvocationMessage { <fields>; }
-keep class com.microsoft.signalr.StreamInvocationMessage { <fields>; }
-keep class com.microsoft.signalr.CancelInvocationMessage { <fields>; }
-keep class com.microsoft.signalr.CompletionMessage { <fields>; }
-keep class com.microsoft.signalr.StreamItem { <fields>; }
-keep class com.microsoft.signalr.PingMessage { <fields>; }
-keep class com.microsoft.signalr.CloseMessage { <fields>; }

# WebRTC's native JNI_OnLoad resolves this bootstrap class and its methods by
# their exact names. The upstream AAR does not publish a consumer keep rule.
-keep class org.jni_zero.JniInit { *; }

# WebRTC reverse-JNI entry points are otherwise invisible to R8. Preserve only
# classes and methods explicitly marked as native callbacks, plus the types in
# their JNI signatures. This covers WebRtcClassLoader and JniHelper without
# retaining the entire org.webrtc package.
-keepclasseswithmembers,allowoptimization,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNative <methods>;
}
-keepclasseswithmembers,allowoptimization,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
-dontwarn org.slf4j.**
