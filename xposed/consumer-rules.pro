# Preserve the libxposed public API surface for module developers
-keep class io.github.libxposed.** { *; }

# Preserve all native methods (HookBridge, ResourcesHook, NativeAPI, etc.)
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Preserve the JNI Hook Trampoline
-keep class org.matrix.vector.impl.hooks.VectorNativeHooker {
    public <init>(java.lang.reflect.Executable);
    public java.lang.Object callback(java.lang.Object[]);
}

# Preserve System Server hooks accessed reflectively by the `zygisk` module
-keep class org.matrix.vector.impl.hookers.HandleSystemServerProcessHooker { *; }
-keep class org.matrix.vector.impl.hookers.HandleSystemServerProcessHooker$Callback { *; }

# Preserve the Initialization entry points called by the `legacy` module
-keep class org.matrix.vector.impl.core.VectorStartup {
    public static <methods>;
}
-keep class org.matrix.vector.impl.di.VectorBootstrap {
    public static <methods>;
}
-keep class org.matrix.vector.impl.di.LegacyFrameworkDelegate { *; }
