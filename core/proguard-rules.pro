-keep class android.** { *; }
-keep class de.robv.android.xposed.** {*;}
-keep class io.github.libxposed.** {*;}
-keep class org.lsposed.lspd.core.* {*;}
-keep class org.lsposed.lspd.hooker.HandleSystemServerProcessHooker {*;}
-keep class org.lsposed.lspd.hooker.HandleSystemServerProcessHooker$Callback {*;}
-keep class org.lsposed.lspd.impl.LSPosedBridge$NativeHooker {*;}
-keep class org.lsposed.lspd.impl.LSPosedBridge$HookerCallback {*;}
-keep class org.lsposed.lspd.util.Hookers {*;}

-keepnames class org.lsposed.lspd.impl.LSPosedHelper {
    public <methods>;
}

-keepattributes RuntimeVisibleAnnotations
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class org.lsposed.lspd.impl.LSPosedContext {
    public <methods>;
}
-keepclassmembers class org.lsposed.lspd.impl.LSPosedHookCallback {
    public <methods>;
}
-keep,allowoptimization,allowobfuscation @io.github.libxposed.api.annotations.* class * {
    @io.github.libxposed.api.annotations.BeforeInvocation <methods>;
    @io.github.libxposed.api.annotations.AfterInvocation <methods>;
}
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
-repackageclasses
-allowaccessmodification
