package org.matrix.vector

import android.os.IBinder
import android.os.Process
import org.lsposed.lspd.core.ApplicationServiceClient.serviceClient
import org.lsposed.lspd.util.Utils
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.core.Startup

/**
 * Main entry point for the Java-side loader. This class is invoked via JNI from the Vector Zygisk
 * module.
 */
object Main {

    /**
     * Shared initialization logic for both System Server and Application processes.
     *
     * @param isSystem True if this is the system_server process.
     * @param niceName The process name (e.g., package name or "system").
     * @param appDir The application's data directory.
     * @param binder The Binder token associated with the application service.
     */
    @JvmStatic
    fun forkCommon(isSystem: Boolean, niceName: String, appDir: String?, binder: IBinder) {
        // Step 1: Initialize system-specific resolution hooks if in system_server
        if (isSystem) {
            ParasiticManagerSystemHooker.start()
        }

        // Step 2: Initialize Xposed bridge components
        val appService = ILSPApplicationService.Stub.asInterface(binder)
        Startup.initXposed(isSystem, niceName, appDir, appService)

        // Step 3: Configure logging levels from the service client
        runCatching { Utils.Log.muted = serviceClient.isLogMuted }
            .onFailure { t -> Utils.logE("Failed to configure logs from service", t) }

        // Step 4: Check if this process is the designated LSPosed Manager.
        // If so, we perform "parasitic" injection into a host (com.android.shell)
        // and terminate further standard Xposed loading for this specific process.
        if (
            niceName == "org.matrix.vector.manager" && ParasiticManagerHooker.start()
        ) {
            Utils.logI("Parasitic manager loaded into host, skipping standard bootstrap.")
            return
        }

        // Step 5: Standard Xposed module loading for third-party apps
        Utils.logI("Loading Vector/Xposed for $niceName (UID: ${Process.myUid()})")
        Startup.bootstrapXposed()
    }
}
