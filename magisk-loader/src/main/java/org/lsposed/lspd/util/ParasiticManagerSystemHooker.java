package org.lsposed.lspd.util;

import android.annotation.SuppressLint;
import android.app.ProfilerInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.util.Log;

import org.lsposed.lspd.hooker.HandleSystemServerProcessHooker;
import org.lsposed.lspd.impl.LSPosedHelper;
import org.lsposed.lspd.service.BridgeService;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

public class ParasiticManagerSystemHooker implements HandleSystemServerProcessHooker.Callback {
    public static void start() {
        HandleSystemServerProcessHooker.callback = new ParasiticManagerSystemHooker();
    }

    /*@XposedHooker
    private static class Hooker2 implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeHookedMethod(XposedInterface.BeforeHookCallback callback) throws Throwable {
            Log.d("LSPosed", "checking new activity");
            var self = callback.getThisObject();
            if (self == null) return;
            var request = XposedHelpers.getObjectField(self, "mRequest");
            Log.d("LSPosed", "start activity intent=" + XposedHelpers.getObjectField(request, "intent") + " ai=" + XposedHelpers.getObjectField(request, "activityInfo"), new Throwable());
        }
    }*/

    @XposedHooker
    private static class Hooker implements XposedInterface.Hooker {
        @AfterInvocation
        public static void afterHookedMethod(XposedInterface.AfterHookCallback callback) throws Throwable {
            Log.d("LSPosed", "checking new activity");
            var intent = (Intent) callback.getArgs()[0];
            Log.d("LSPosed", "intent=" + intent);
            if (intent == null) return;
            // TODO: keep sync with LSPManagerService getManagerIntent
            if (!intent.hasCategory("org.lsposed.manager.LAUNCH_MANAGER")) return;
            var aInfo = (ActivityInfo) callback.getResult();
            if (aInfo == null || !"com.android.shell".equals(aInfo.packageName)) return;
            aInfo.processName = "org.lsposed.manager";
            aInfo.theme = android.R.style.Theme_Material_Light_NoActionBar;
            aInfo.flags = aInfo.flags & ~(ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS | ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS);
            BridgeService.getService().preStartManager();
            Log.d("LSPosed", "replaced activity");
        }
    }

    @SuppressLint("PrivateApi")
    @Override
    public void onSystemServerLoaded(ClassLoader classLoader) {
        try {
            Class<?> supervisorClass;
            try {
                supervisorClass = Class.forName("com.android.server.wm.ActivityTaskSupervisor", false, classLoader);
            } catch (ClassNotFoundException ignore) {
                supervisorClass = Class.forName("com.android.server.wm.ActivityStackSupervisor", false, classLoader);
            }
            LSPosedHelper.hookMethod(Hooker.class, supervisorClass, "resolveActivity", Intent.class, ResolveInfo.class, int.class, ProfilerInfo.class);
            /*
            for (var method: Class.forName("com.android.server.wm.ActivityStarter", false, classLoader).getDeclaredMethods()) {
                if ("execute".equals(method.getName()))
                    HookBridge.deoptimizeMethod(method);
            }
            LSPosedHelper.hookAllMethods(Hooker2.class, Class.forName("com.android.server.wm.ActivityStarter", false, classLoader), "execute");*/
            Log.d("LSPosed", "hooked activity starter");
        } catch (Throwable e) {
            Log.e("LSPosed", "onSystemServerLoaded: ", e);
        }
    }
}
