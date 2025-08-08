# Add these rules to your proguard-rules.pro file to prevent WebRTC crashes

# Keep WebRTC classes
-keep class org.webrtc.** { *; }
-keep class com.google.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep your model classes
-keep class com.example.team_talk_kotlin.data.model.** { *; }
-keep class com.example.team_talk_kotlin.data.webrtc.** { *; }

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Prevent optimization of classes with native methods
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}