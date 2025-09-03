package org.lsposed.lspd.hooker;

import android.app.ActivityThread;

import androidx.annotation.NonNull;

import de.robv.android.xposed.XposedInit;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.annotations.XposedHooker;

@XposedHooker
public class AttachHooker implements XposedInterface.Hooker {

    public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
        XposedInit.loadModules((ActivityThread) callback.getThisObject());
    }
}
