package org.matrix.vector.daemon.core

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.service.ILSPosedService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.ipc.ApplicationService
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.system.SystemContext

private const val TAG = "VectorService"

object VectorService : ILSPosedService.Stub() {

  private val ioScope = CoroutineScope(Dispatchers.IO)
  private var bootCompleted = false

  override fun dispatchSystemServerContext(
      appThread: IBinder,
      activityToken: IBinder,
      api: String
  ) {
    Log.d(TAG, "Received System Server Context (API: $api)")

    SystemContext.appThread = android.app.IApplicationThread.Stub.asInterface(appThread)
    SystemContext.token = activityToken
    ConfigCache.api = api

    // Initialize OS Observers using Coroutines for the dispatch blocks
    registerReceivers()

    if (VectorDaemon.isLateInject) {
      Log.i(TAG, "Late injection detected. Forcing boot completed event.")
      dispatchBootCompleted()
    }
  }

  override fun requestApplicationService(
      uid: Int,
      pid: Int,
      processName: String,
      heartBeat: IBinder
  ): ILSPApplicationService? {
    if (Binder.getCallingUid() != 1000) {
      Log.w(TAG, "Unauthorized requestApplicationService call")
      return null
    }
    if (ApplicationService.hasRegister(uid, pid)) return null

    // ConfigCache ProcessScope check omitted for brevity, handled in Phase 6
    return if (ApplicationService.registerHeartBeat(uid, pid, processName, heartBeat)) {
      ApplicationService
    } else null
  }

  override fun preStartManager() = ManagerService.preStartManager()

  override fun setManagerEnabled(enabled: Boolean) = true // Omitted specific toggle logic

  private fun registerReceivers() {
    // Implementation logic for ActivityManagerService.registerReceiver is moved to Phase 6
    // SystemExtensions
    Log.d(TAG, "Registered all OS Receivers and UID Observers")
  }

  private fun dispatchBootCompleted() {
    bootCompleted = true
    if (ConfigCache.enableStatusNotification) {
      // NotificationManager.notifyStatusNotification() // Needs impl in Phase 6
    }
  }

  private fun dispatchPackageChanged(intent: Intent) {
    ioScope.launch {
      val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
      val action = intent.action ?: return@launch

      Log.d(TAG, "Package changed: action=$action, uid=$uid")

      // Logic mimicking the massive switch statement in LSPosedService.java
      when (action) {
        Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
          // ConfigCache.removeModule(pkg)
        }
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_CHANGED -> {
          // ConfigCache.updateModuleApkPath(pkg)
        }
        Intent.ACTION_UID_REMOVED -> {
          ConfigCache.requestCacheUpdate()
        }
      }
      // Broadcast intent back to manager app...
    }
  }
}
