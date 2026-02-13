/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;

import android.annotation.SuppressLint;

import org.lsposed.lspd.deopt.PrebuiltMethodsDeopter;
import org.lsposed.lspd.impl.LSPosedHelper;
import org.lsposed.lspd.util.Hookers;

import io.github.libxposed.api.XposedInterface;

// system_server initialization
public class HandleSystemServerProcessHooker implements XposedInterface.Hooker {

    public interface Callback {
        void onSystemServerLoaded(ClassLoader classLoader);
    }

    public static volatile ClassLoader systemServerCL;
    public static volatile Callback callback = null;

    @SuppressLint("PrivateApi")
    public static void after() {
        Hookers.logD("ZygoteInit#handleSystemServerProcess() starts");
        try {
            // get system_server classLoader
            systemServerCL = Thread.currentThread().getContextClassLoader();
            // deopt methods in SYSTEMSERVERCLASSPATH
            PrebuiltMethodsDeopter.deoptSystemServerMethods(systemServerCL);
            var clazz = Class.forName("com.android.server.SystemServer", false, systemServerCL);
            LSPosedHelper.hookAllMethods(StartBootstrapServicesHooker.class, clazz, "startBootstrapServices");
            if (callback != null) callback.onSystemServerLoaded(systemServerCL);
        } catch (Throwable t) {
            Hookers.logE("error when hooking systemMain", t);
        }
    }
}
