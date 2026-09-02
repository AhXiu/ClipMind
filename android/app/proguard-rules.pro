# Retrofit API annotations and generic signatures are inspected at runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep interface com.clipmind.android.network.** { *; }
-keep class com.clipmind.android.network.dto.** { *; }

# AIDL entrypoint is constructed by Shizuku in a separate process.
-keep class com.clipmind.android.shizuku.ClipboardUserService { public <init>(android.content.Context); }
-keep class com.clipmind.android.shizuku.IClipboardUserService$Stub { *; }
