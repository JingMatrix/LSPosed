package org.lsposed.lspd.hooker;

import org.lsposed.lspd.impl.LSPosedBridge;
import org.lsposed.lspd.util.Utils.Log;

import io.github.libxposed.api.XposedInterface;

public class CrashDumpHooker implements XposedInterface.Hooker {

    public static void before(XposedInterface.BeforeHookCallback callback) {
        try {
            var e = (Throwable) callback.getArgs()[0];
            LSPosedBridge.log("Crash unexpectedly: " + Log.getStackTraceString(e));
        } catch (Throwable ignored) {
        }
    }
}
