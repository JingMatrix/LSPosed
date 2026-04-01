package org.matrix.vector.daemon

import android.app.ActivityManager
import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.CliSocketServer
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.ipc.BRIDGE_TRANSACTION_CODE
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService
import org.matrix.vector.daemon.utils.applyNotificationWorkaround

private const val TAG = "VectorDaemon"
private const val ACTION_SEND_BINDER = 1

object VectorDaemon {
  private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
    Log.e(TAG, "Caught fatal coroutine exception in background task!", throwable)
  }

  // Dispatchers.IO: Uses the shared background thread pool.
  // SupervisorJob(): Ensures one failing task doesn't kill the whole daemon.
  val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

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

    // Start Environmental Daemons
    LogcatMonitor.start()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.start()
    CliSocketServer.start()

    // Preload Framework DEX in the background
    scope.launch { FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) }

    // Setup Main Looper & System Services
    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    @Suppress("DEPRECATION") Looper.prepareMainLooper()

    val systemServerService = SystemServerService(systemServerMaxRetry, proxyServiceName)
    systemServerService.putBinderForSystemServer()

    // Initializes system frameworks inside the daemon process
    ActivityThread.systemMain()
    DdmHandleAppName.setAppName("org.matrix.vector.daemon", 0)

    //  Wait for Android Core Services
    waitForSystemService("package")
    waitForSystemService("activity")
    waitForSystemService(Context.USER_SERVICE)
    waitForSystemService(Context.APP_OPS_SERVICE)

    applyNotificationWorkaround()

    // Inject Vector into system_server
    sendToBridge(VectorService.asBinder(), isRestart = false, systemServerService)

    if (!ManagerService.isVerboseLog()) {
      LogcatMonitor.stopVerbose()
    }

    Looper.loop()
    throw RuntimeException("Main thread loop unexpectedly exited")
  }

  private fun waitForSystemService(name: String) = runBlocking {
    while (ServiceManager.getService(name) == null) {
      Log.i(TAG, "Waiting system service: $name for 1s")
      delay(1000)
    }
  }

  @Suppress("DEPRECATION")
  private fun sendToBridge(
      binder: IBinder,
      isRestart: Boolean,
      systemServerService: SystemServerService
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "sendToBridge MUST run on the main thread!"
    }

    Os.seteuid(0)

    runCatching {
          var bridgeService: IBinder?
          if (isRestart) Log.w(TAG, "System Server restarted...")

          while (true) {
            bridgeService = ServiceManager.getService("activity")
            if (bridgeService?.pingBinder() == true) break
            Log.i(TAG, "activity service not ready, waiting 1s...")
            Thread.sleep(1000)
          }

          // Setup death recipient to handle system_server crashes
          val deathRecipient =
              object : IBinder.DeathRecipient {
                override fun binderDied() {
                  Log.w(TAG, "System Server died! Clearing caches and re-injecting...")
                  bridgeService.unlinkToDeath(this, 0)
                  clearSystemCaches()
                  systemServerService.putBinderForSystemServer()
                  ManagerService.guard = null // Remove dead guard
                  Handler(Looper.getMainLooper()).post {
                    sendToBridge(binder, isRestart = true, systemServerService)
                  }
                }
              }
          bridgeService.linkToDeath(deathRecipient, 0)

          // Try sending the Binder payload (up to 3 times)
          var success = false
          for (i in 0 until 3) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
              data.writeInt(ACTION_SEND_BINDER)
              data.writeStrongBinder(binder)
              success = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0) == true
              reply.readException()
              if (success) break
            } finally {
              data.recycle()
              reply.recycle()
            }
            Log.w(TAG, "No response from bridge, retrying...")
            Thread.sleep(1000)
          }

          if (success) Log.i(TAG, "Successfully injected Vector into system_server")
          else {
            Log.e(TAG, "Failed to inject Vector into system_server")
            systemServerService.maybeRetryInject()
          }
        }
        .onFailure { Log.e(TAG, "Error during System Server bridging", it) }
    Os.seteuid(1000)
  }

  private fun clearSystemCaches() {
    Log.i(TAG, "Clearing ServiceManager and ActivityManager caches...")
    runCatching {
          // Clear ServiceManager.sServiceManager
          var field = ServiceManager::class.java.getDeclaredField("sServiceManager")
          field.isAccessible = true
          field.set(null, null)

          // Clear ServiceManager.sCache
          field = ServiceManager::class.java.getDeclaredField("sCache")
          field.isAccessible = true
          val sCache = field.get(null)
          if (sCache is MutableMap<*, *>) {
            sCache.clear()
          }

          // Clear ActivityManager.IActivityManagerSingleton
          field = ActivityManager::class.java.getDeclaredField("IActivityManagerSingleton")
          field.isAccessible = true
          val singleton = field.get(null)
          if (singleton != null) {
            val mInstanceField =
                Class.forName("android.util.Singleton").getDeclaredField("mInstance")
            mInstanceField.isAccessible = true
            synchronized(singleton) { mInstanceField.set(singleton, null) }
          }
        }
        .onFailure { Log.w(TAG, "Failed to clear system caches via reflection", it) }
  }
}
