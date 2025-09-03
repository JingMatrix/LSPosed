package org.lsposed.lspd.hooker;

import androidx.annotation.NonNull;

import org.lsposed.lspd.impl.LSPosedBridge;
import org.lsposed.lspd.util.Utils.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.annotations.XposedHooker;

@XposedHooker
public class CrashDumpHooker implements XposedInterface.Hooker {

    public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
        try {
            var e = (Throwable) callback.getArgs()[0];
            LSPosedBridge.log("Crash unexpectedly: " + Log.getStackTraceString(e));
        } catch (Throwable ignored) {
        }
    }
}
