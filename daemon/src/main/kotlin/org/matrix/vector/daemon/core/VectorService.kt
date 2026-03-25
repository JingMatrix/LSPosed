package org.matrix.vector.daemon.core

import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import hidden.HiddenApiBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.service.ILSPosedService
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.ProcessScope
import org.matrix.vector.daemon.ipc.ApplicationService
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.ModuleService
import org.matrix.vector.daemon.system.*

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

    val scope = ProcessScope(processName, uid)
    if (!ManagerService.shouldStartManager(pid, uid, processName) &&
        ConfigCache.shouldSkipProcess(scope)) {
      Log.d(TAG, "Skipped $processName/$uid")
      return null
    }

    return if (ApplicationService.registerHeartBeat(uid, pid, processName, heartBeat)) {
      ApplicationService
    } else null
  }

  override fun preStartManager() = ManagerService.preStartManager()

  override fun setManagerEnabled(enabled: Boolean) = true // Omitted specific toggle logic

  private fun registerReceivers() {
    val packageFilter =
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_CHANGED)
          addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
          addDataScheme("package")
        }
    val uidFilter = IntentFilter(Intent.ACTION_UID_REMOVED)

    val receiver =
        object : IIntentReceiver.Stub() {
          override fun performReceive(
              intent: Intent,
              resultCode: Int,
              data: String?,
              extras: Bundle?,
              ordered: Boolean,
              sticky: Boolean,
              sendingUser: Int
          ) {
            ioScope.launch { dispatchPackageChanged(intent) }
          }
        }
    activityManager?.registerReceiverCompat(receiver, packageFilter, null, -1, 0)
    activityManager?.registerReceiverCompat(receiver, uidFilter, null, -1, 0)

    // UID Observer
    val uidObserver =
        object : android.app.IUidObserver.Stub() {
          override fun onUidActive(uid: Int) = ModuleService.uidStarts(uid)

          override fun onUidCachedChanged(uid: Int, cached: Boolean) {
            if (!cached) ModuleService.uidStarts(uid)
          }

          override fun onUidIdle(uid: Int, disabled: Boolean) = ModuleService.uidStarts(uid)

          override fun onUidGone(uid: Int, disabled: Boolean) = ModuleService.uidGone(uid)
        }

    // UID_OBSERVER_ACTIVE | UID_OBSERVER_GONE | UID_OBSERVER_IDLE | UID_OBSERVER_CACHED
    val which = 1 or 2 or 4 or 8
    activityManager?.registerUidObserverCompat(
        uidObserver, which, HiddenApiBridge.ActivityManager_PROCESS_STATE_UNKNOWN())
    Log.d(TAG, "Registered all OS Receivers and UID Observers")
  }

  private fun dispatchBootCompleted() {
    bootCompleted = true
    if (ConfigCache.enableStatusNotification) {
      // NotificationManager.notifyStatusNotification() // Needs impl in Phase 6
    }
  }

  private fun dispatchPackageChanged(intent: Intent) {
    val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
    val action = intent.action ?: return
    val userId = intent.getIntExtra("android.intent.extra.user_handle", uid % PER_USER_RANGE)
    val uri = intent.data
    val moduleName = uri?.schemeSpecificPart ?: ConfigCache.getModuleByUid(uid)?.packageName

    var isXposedModule = false
    if (moduleName != null) {
      val appInfo =
          packageManager
              ?.getPackageInfoCompat(moduleName, MATCH_ALL_FLAGS or PackageManager.GET_META_DATA, 0)
              ?.applicationInfo
      isXposedModule =
          appInfo != null &&
              ((appInfo.metaData?.containsKey("xposedminversion") == true) ||
                  ConfigCache.getModuleApkPath(appInfo) != null)
    }

    when (action) {
      Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
        if (moduleName != null &&
            intent.getBooleanExtra("android.intent.extra.REMOVED_FOR_ALL_USERS", false)) {
          if (ConfigCache.removeModule(moduleName)) isXposedModule = true
        }
      }
      Intent.ACTION_PACKAGE_ADDED,
      Intent.ACTION_PACKAGE_CHANGED -> {
        if (isXposedModule && moduleName != null) {
          val appInfo =
              packageManager?.getPackageInfoCompat(moduleName, MATCH_ALL_FLAGS, 0)?.applicationInfo
          if (appInfo != null) {
            isXposedModule =
                ConfigCache.updateModuleApkPath(
                    moduleName, ConfigCache.getModuleApkPath(appInfo), false)
          }
        } else if (ConfigCache.cachedScopes.keys.any { it.uid == uid }) {
          ConfigCache.requestCacheUpdate()
        }
      }
      Intent.ACTION_UID_REMOVED -> {
        if (isXposedModule) ConfigCache.requestCacheUpdate()
        else if (ConfigCache.cachedScopes.keys.any { it.uid == uid })
            ConfigCache.requestCacheUpdate()
      }
    }

    // Broadcast back to Manager
    if (moduleName != null) {
      val notifyIntent =
          Intent("org.lsposed.manager.NOTIFICATION").apply {
            putExtra(Intent.EXTRA_INTENT, intent)
            putExtra("android.intent.extra.PACKAGES", moduleName)
            putExtra(Intent.EXTRA_USER, userId)
            putExtra("isXposedModule", isXposedModule)
            addFlags(
                0x01000000 or
                    0x00400000) // FLAG_RECEIVER_INCLUDE_BACKGROUND | FLAG_RECEIVER_FROM_SHELL
            setPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME)
          }
      activityManager?.broadcastIntentCompat(notifyIntent)
    }
  }
}
