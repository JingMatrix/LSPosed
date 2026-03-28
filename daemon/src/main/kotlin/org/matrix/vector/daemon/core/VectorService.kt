package org.matrix.vector.daemon.core

import android.app.IApplicationThread
import android.content.Context
import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import hidden.HiddenApiBridge
import io.github.libxposed.service.IXposedScopeCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.service.ILSPosedService
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
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
      appThread: IBinder?,
      activityToken: IBinder?,
  ) {
    appThread?.let { SystemContext.appThread = IApplicationThread.Stub.asInterface(it) }
    SystemContext.token = activityToken

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

  private fun createReceiver() =
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
          ioScope.launch {
            when (intent.action) {
              Intent.ACTION_LOCKED_BOOT_COMPLETED -> dispatchBootCompleted()
              NotificationManager.openManagerAction -> ManagerService.openManager(intent.data)
              NotificationManager.moduleScopeAction -> dispatchModuleScope(intent)
              else -> dispatchPackageChanged(intent)
            }
          }

          // Critical for ordered broadcasts to avoid freezing the system queue
          if (!ordered && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
          runCatching {
                val appThread = SystemContext.appThread
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                  activityManager?.finishReceiver(
                      appThread?.asBinder(), resultCode, data, extras, false, intent.flags)
                } else {
                  activityManager?.finishReceiver(
                      this, resultCode, data, extras, false, intent.flags)
                }
              }
              .onFailure { Log.e(TAG, "finishReceiver failed", it) }
        }
      }

  private fun registerReceivers() {
    val packageFilter =
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_CHANGED)
          addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
          addDataScheme("package")
        }

    val uidFilter = IntentFilter(Intent.ACTION_UID_REMOVED)

    val bootFilter =
        IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED).apply {
          priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }

    val openManagerNoDataFilter = IntentFilter(NotificationManager.openManagerAction)

    val openManagerDataFilter =
        IntentFilter(NotificationManager.openManagerAction).apply {
          addDataScheme("module")
          addDataScheme("android_secret_code")
        }

    val scopeFilter =
        IntentFilter(NotificationManager.moduleScopeAction).apply { addDataScheme("module") }
    val secretCodeFilter =
        IntentFilter("android.provider.Telephony.SECRET_CODE").apply {
          addDataScheme("android_secret_code")
          addDataAuthority("5776733", null)
        }

    // Define strict Android 14+ flags and the system-only BRICK permission
    val notExported = Context.RECEIVER_NOT_EXPORTED
    val exported = Context.RECEIVER_EXPORTED
    val brickPerm = "android.permission.BRICK"

    activityManager?.registerReceiverCompat(
        createReceiver(), packageFilter, brickPerm, -1, notExported)
    activityManager?.registerReceiverCompat(createReceiver(), uidFilter, brickPerm, -1, notExported)
    activityManager?.registerReceiverCompat(createReceiver(), bootFilter, brickPerm, 0, notExported)

    activityManager?.registerReceiverCompat(
        createReceiver(), openManagerNoDataFilter, brickPerm, 0, notExported)
    activityManager?.registerReceiverCompat(
        createReceiver(), openManagerDataFilter, brickPerm, 0, notExported)
    activityManager?.registerReceiverCompat(
        createReceiver(), scopeFilter, brickPerm, 0, notExported)

    // Only the secret dialer code needs to be exported so the phone app can trigger it
    activityManager?.registerReceiverCompat(
        createReceiver(),
        secretCodeFilter,
        "android.permission.CONTROL_INCALL_EXPERIENCE",
        0,
        exported)

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

    val which =
        HiddenApiBridge.ActivityManager_UID_OBSERVER_ACTIVE() or
            HiddenApiBridge.ActivityManager_UID_OBSERVER_GONE() or
            HiddenApiBridge.ActivityManager_UID_OBSERVER_IDLE() or
            HiddenApiBridge.ActivityManager_UID_OBSERVER_CACHED()

    activityManager?.registerUidObserverCompat(
        uidObserver, which, HiddenApiBridge.ActivityManager_PROCESS_STATE_UNKNOWN())
    Log.d(TAG, "Registered all OS Receivers and UID Observers")
  }

  private fun dispatchBootCompleted() {
    bootCompleted = true
    if (PreferenceStore.isStatusNotificationEnabled()) {
      NotificationManager.cancelStatusNotification()
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
          if (ModuleDatabase.removeModule(moduleName)) isXposedModule = true
        }
      }
      Intent.ACTION_PACKAGE_ADDED,
      Intent.ACTION_PACKAGE_CHANGED -> {
        if (isXposedModule && moduleName != null) {
          val appInfo =
              packageManager?.getPackageInfoCompat(moduleName, MATCH_ALL_FLAGS, 0)?.applicationInfo
          if (appInfo != null) {
            isXposedModule =
                ModuleDatabase.updateModuleApkPath(
                    moduleName, ConfigCache.getModuleApkPath(appInfo), false)
          }
        } else if (ConfigCache.state.scopes.keys.any { it.uid == uid }) {
          ConfigCache.requestCacheUpdate()
        }
      }
      Intent.ACTION_UID_REMOVED -> {
        if (isXposedModule) ConfigCache.requestCacheUpdate()
        else if (ConfigCache.state.scopes.keys.any { it.uid == uid })
            ConfigCache.requestCacheUpdate()
      }
    }

    val removed =
        action == Intent.ACTION_PACKAGE_FULLY_REMOVED || action == Intent.ACTION_UID_REMOVED
    if (moduleName == BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME && userId == 0) {
      Log.d(TAG, "Manager updated")
      ConfigCache.updateManager(removed)
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

  @Suppress("UNCHECKED_CAST")
  private fun dispatchModuleScope(intent: Intent) {
    val data = intent.data ?: return
    val extras = intent.extras ?: return
    val callbackBinder = extras.getBinder("callback") ?: return
    if (!callbackBinder.isBinderAlive) return

    val authority = data.encodedAuthority ?: return
    val parts = authority.split(":", limit = 2)
    if (parts.size != 2) return
    val packageName = parts[0]
    val userId = parts[1].toIntOrNull() ?: return

    val scopePackageName = data.path?.substring(1) ?: return // remove leading '/'
    val action = data.getQueryParameter("action") ?: return

    val iCallback = IXposedScopeCallback.Stub.asInterface(callbackBinder)
    runCatching {
          val appInfo = packageManager?.getPackageInfoCompat(scopePackageName, 0, userId)
          if (appInfo == null) {
            iCallback.onScopeRequestFailed("Package not found")
            return
          }
          when (action) {
            "approve" -> {
              val scopes = ConfigCache.getModuleScope(packageName) ?: mutableListOf()
              if (scopes.none { it.packageName == scopePackageName && it.userId == userId }) {
                scopes.add(
                    org.lsposed.lspd.models.Application().apply {
                      this.packageName = scopePackageName
                      this.userId = userId
                    })
                ModuleDatabase.setModuleScope(packageName, scopes)
              }
              iCallback.onScopeRequestApproved(listOf(scopePackageName))
            }
            "deny" -> iCallback.onScopeRequestFailed("Request denied by user")
            "delete" -> iCallback.onScopeRequestFailed("Request timeout")
            "block" -> {
              val blocked =
                  PreferenceStore.getModulePrefs("lspd", 0, "config")["scope_request_blocked"]
                      as? Set<String> ?: emptySet()
              PreferenceStore.updateModulePref(
                  "lspd", 0, "config", "scope_request_blocked", blocked + packageName)
              iCallback.onScopeRequestFailed("Request blocked by configuration")
            }
          }
        }
        .onFailure { runCatching { iCallback.onScopeRequestFailed(it.message) } }

    NotificationManager.cancelNotification(
        NotificationManager.SCOPE_CHANNEL_ID, packageName, userId)
  }
}
