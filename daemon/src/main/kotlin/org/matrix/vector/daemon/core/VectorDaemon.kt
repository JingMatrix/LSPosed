package org.matrix.vector.daemon.core

import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService
import org.matrix.vector.daemon.utils.applyNotificationWorkaround

private const val TAG = "VectorDaemon"

object VectorDaemon {
  var isLateInject = false
  var proxyServiceName = "serial"

  @JvmStatic
  fun main(args: Array<String>) {
    if (!FileSystem.tryLock()) kotlin.system.exitProcess(0)

    var systemServerMaxRetry = 1
    for (arg in args) {
      if (arg.startsWith("--system-server-max-retry=")) {
        systemServerMaxRetry = arg.substringAfter('=').toIntOrNull() ?: 1
      } else if (arg == "--late-inject") {
        isLateInject = true
        proxyServiceName = "serial_vector"
      }
    }

    Log.i(TAG, "Vector daemon started: lateInject=$isLateInject, proxy=$proxyServiceName")
    Log.i(TAG, "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      Log.e(TAG, "Uncaught exception in Daemon", e)
      kotlin.system.exitProcess(1)
    }

    // 1. Start Environmental Daemons
    LogcatMonitor.start()
    if (ConfigCache.isLogWatchdogEnabled())
        LogcatMonitor.enableWatchdog() // Needs impl in LogcatMonitor

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      Dex2OatServer.start()
    }

    // 2. Setup Main Looper & System Services
    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    @Suppress("DEPRECATION")
    Looper.prepareMainLooper()

    val systemServerService = SystemServerService(systemServerMaxRetry, proxyServiceName)
    systemServerService.putBinderForSystemServer()

    // Initializes system frameworks inside the daemon process
    ActivityThread.systemMain()
    DdmHandleAppName.setAppName("org.matrix.vector.daemon", 0)

    // 3. Wait for Android Core Services
    waitForSystemService("package")
    waitForSystemService("activity")
    waitForSystemService(Context.USER_SERVICE)
    waitForSystemService(Context.APP_OPS_SERVICE)

    applyNotificationWorkaround()

    // 4. Inject Vector into system_server
    SystemServerBridge.sendToBridge(
        VectorService.asBinder(), isRestart = false, systemServerService)

    if (!ManagerService.isVerboseLog()) {
      LogcatMonitor.stopVerbose() // Needs impl in LogcatMonitor
    }

    Looper.loop()
    throw RuntimeException("Main thread loop unexpectedly exited")
  }

  private fun waitForSystemService(name: String) = runBlocking {
    while (android.os.ServiceManager.getService(name) == null) {
      Log.i(TAG, "Waiting for system service: $name")
      delay(1000)
    }
  }
}
