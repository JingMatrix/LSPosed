package org.lsposed.lspd.hooker;

import android.app.ActivityThread;

import androidx.annotation.NonNull;

import org.lsposed.lspd.annotation.XposedHooker;

import de.robv.android.xposed.XposedInit;
import io.github.libxposed.api.XposedInterface;

@XposedHooker
public class AttachHooker implements XposedInterface.Hooker {

    public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
        XposedInit.loadModules((ActivityThread) callback.getThisObject());
    }
}
