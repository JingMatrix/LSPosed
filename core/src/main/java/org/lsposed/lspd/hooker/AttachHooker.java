package org.lsposed.lspd.hooker;

import android.app.ActivityThread;

import de.robv.android.xposed.XposedInit;
import io.github.libxposed.api.XposedInterface;

public class AttachHooker implements XposedInterface.Hooker {

    public static void after(XposedInterface.AfterHookCallback callback) {
        XposedInit.loadModules((ActivityThread) callback.getThisObject());
    }
}
