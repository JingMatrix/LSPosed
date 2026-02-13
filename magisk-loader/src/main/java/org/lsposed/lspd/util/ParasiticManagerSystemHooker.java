package org.lsposed.lspd.util;

import android.annotation.SuppressLint;
import android.app.ProfilerInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import org.lsposed.lspd.hooker.HandleSystemServerProcessHooker;
import org.lsposed.lspd.impl.LSPosedHelper;
import org.lsposed.lspd.service.BridgeService;
import org.lsposed.lspd.util.Utils;

import io.github.libxposed.api.XposedInterface;


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

    private static class Hooker implements XposedInterface.Hooker {
        public static void after(XposedInterface.AfterHookCallback callback) throws Throwable {
            var intent = (Intent) callback.getArgs()[0];
            if (intent == null) return;
            if (!intent.hasCategory("org.lsposed.manager.LAUNCH_MANAGER")) return;
            var aInfo = (ActivityInfo) callback.getResult();
            if (aInfo == null || !"com.android.shell".equals(aInfo.packageName)) return;
            // We shouldn't pollute system's object
            aInfo = new ActivityInfo(aInfo);
            // use a different process name
            aInfo.processName = "org.lsposed.manager";
            // choose a theme that has transition animation
            aInfo.theme = android.R.style.Theme_DeviceDefault_Settings;
            // remove some annoying flags
            aInfo.flags = aInfo.flags & ~(ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS | ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS);
            BridgeService.getService().preStartManager();
            callback.setResult(aInfo);
        }
    }

    @SuppressLint("PrivateApi")
    @Override
    public void onSystemServerLoaded(ClassLoader classLoader) {
        try {
            Class<?> supervisorClass;
            try {
                // 14-12.0
                supervisorClass = Class.forName("com.android.server.wm.ActivityTaskSupervisor", false, classLoader);
            } catch (ClassNotFoundException ignore) {
                try {
                    // 11-10
                    supervisorClass = Class.forName("com.android.server.wm.ActivityStackSupervisor", false, classLoader);
                } catch (ClassNotFoundException ignore2) {
                    // 9-8.1
                    supervisorClass = Class.forName("com.android.server.am.ActivityStackSupervisor", false, classLoader);
                }
            }
            LSPosedHelper.hookMethod(Hooker.class, supervisorClass, "resolveActivity", Intent.class, ResolveInfo.class, int.class, ProfilerInfo.class);
            /*
            for (var method: Class.forName("com.android.server.wm.ActivityStarter", false, classLoader).getDeclaredMethods()) {
                if ("execute".equals(method.getName()))
                    HookBridge.deoptimizeMethod(method);
            }
            LSPosedHelper.hookAllMethods(Hooker2.class, Class.forName("com.android.server.wm.ActivityStarter", false, classLoader), "execute");*/
            Utils.logD("hooked activity starter");
        } catch (Throwable e) {
            Utils.logE("onSystemServerLoaded: ", e);
        }
    }
}
