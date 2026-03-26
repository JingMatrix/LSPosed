package org.matrix.vector.daemon.ipc

import android.content.ComponentName
import android.content.Context
import android.content.IIntentSender
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.VersionedPackage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemProperties
import android.util.Log
import android.view.IWindowManager
import io.github.libxposed.service.IXposedService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.UserInfo
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.system.*
import org.matrix.vector.daemon.utils.getRealUsers
import rikka.parcelablelist.ParcelableListSlice

private const val TAG = "VectorManagerService"

object ManagerService : ILSPManagerService.Stub() {

  @Volatile var _isVerboseLog = false
  @Volatile private var managerPid = -1
  @Volatile private var pendingManager = false
  @Volatile private var isEnabled = true

  private var managerIntent: Intent? = null

  var guard: ManagerGuard? = null
    internal set

  class ManagerGuard(private val binder: IBinder, val pid: Int, val uid: Int) :
      IBinder.DeathRecipient {
    private val connection =
        object : android.app.IServiceConnection.Stub() {
          override fun connected(name: ComponentName?, service: IBinder?, dead: Boolean) {}
        }

    init {
      ManagerService.guard = this
      runCatching {
            binder.linkToDeath(this, 0)
            // MIUI XSpace Workaround
            if (Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
              val intent =
                  Intent().apply {
                    component =
                        ComponentName.unflattenFromString(
                            "com.miui.securitycore/com.miui.xspace.service.XSpaceService")
                  }
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activityManager?.bindService(
                    SystemContext.appThread,
                    SystemContext.token,
                    intent,
                    intent.type,
                    connection,
                    Context.BIND_AUTO_CREATE.toLong(),
                    "android",
                    0)
              } else {
                activityManager?.bindService(
                    SystemContext.appThread,
                    SystemContext.token,
                    intent,
                    intent.type,
                    connection,
                    Context.BIND_AUTO_CREATE,
                    "android",
                    0)
              }
            }
          }
          .onFailure {
            Log.e(TAG, "ManagerGuard initialization failed", it)
            ManagerService.guard = null
          }
    }

    override fun binderDied() {
      runCatching {
        binder.unlinkToDeath(this, 0)
        activityManager?.unbindService(connection)
      }
      ManagerService.guard = null
    }
  }

  @Synchronized
  fun preStartManager(): Boolean {
    pendingManager = true
    managerPid = -1
    return true
  }

  @Synchronized
  fun shouldStartManager(pid: Int, uid: Int, processName: String): Boolean {
    if (!isEnabled ||
        uid != BuildConfig.MANAGER_INJECTED_UID ||
        processName != BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME ||
        !pendingManager)
        return false
    pendingManager = false
    managerPid = pid
    return true
  }

  private fun getManagerIntent(): Intent? {
    if (managerIntent != null) return managerIntent
    runCatching {
          var intent =
              Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_INFO)
                setPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME)
              }
          var ris = packageManager?.queryIntentActivitiesCompat(intent, intent.type, 0, 0)

          if (ris.isNullOrEmpty()) {
            intent.removeCategory(Intent.CATEGORY_INFO)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            ris = packageManager?.queryIntentActivitiesCompat(intent, intent.type, 0, 0)
          }

          if (ris.isNullOrEmpty()) {
            val pkgInfo =
                packageManager?.getPackageInfoCompat(
                    BuildConfig.MANAGER_INJECTED_PKG_NAME, PackageManager.GET_ACTIVITIES, 0)
            val activity = pkgInfo?.activities?.firstOrNull { it.processName == it.packageName }
            if (activity != null) {
              intent =
                  Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(activity.packageName, activity.name)
                  }
            } else return null
          } else {
            val activity = ris.first().activityInfo
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.component = ComponentName(activity.packageName, activity.name)
          }

          intent.categories?.clear()
          intent.addCategory("org.lsposed.manager.LAUNCH_MANAGER")
          intent.setPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME)
          managerIntent = Intent(intent)
        }
        .onFailure { Log.e(TAG, "Failed to build manager intent", it) }
    return managerIntent
  }

  fun openManager(withData: Uri?) {
    val intent = getManagerIntent() ?: return
    val launchIntent = Intent(intent).apply { data = withData }
    runCatching {
          activityManager?.startActivityAsUserWithFeature(
              SystemContext.appThread,
              "android",
              null,
              launchIntent,
              launchIntent.type,
              null,
              null,
              0,
              0,
              null,
              null,
              0)
        }
        .onFailure { Log.e(TAG, "Failed to open manager", it) }
  }

  fun postStartManager(pid: Int, uid: Int): Boolean =
      isEnabled && uid == BuildConfig.MANAGER_INJECTED_UID && pid == managerPid

  fun obtainManagerBinder(heartbeat: IBinder, pid: Int, uid: Int): IBinder {
    ManagerGuard(heartbeat, pid, uid)
    return this
  }

  fun isRunningManager(pid: Int, uid: Int): Boolean = false

  override fun getXposedApiVersion() = IXposedService.LIB_API

  override fun getXposedVersionCode() = BuildConfig.VERSION_CODE

  override fun getXposedVersionName() = BuildConfig.VERSION_NAME

  override fun getApi() = ConfigCache.api

  override fun getInstalledPackagesFromAllUsers(
      flags: Int,
      filterNoProcess: Boolean
  ): ParcelableListSlice<PackageInfo> {
    return ParcelableListSlice(
        packageManager?.getInstalledPackagesForAllUsers(flags, filterNoProcess) ?: emptyList())
  }

  override fun enabledModules() = ConfigCache.getEnabledModules().toTypedArray()

  override fun enableModule(packageName: String) = ConfigCache.enableModule(packageName)

  override fun disableModule(packageName: String) = ConfigCache.disableModule(packageName)

  override fun setModuleScope(packageName: String, scope: MutableList<Application>) =
      ConfigCache.setModuleScope(packageName, scope)

  override fun getModuleScope(packageName: String) = ConfigCache.getModuleScope(packageName)

  override fun isVerboseLog() = _isVerboseLog || BuildConfig.DEBUG

  override fun setVerboseLog(enabled: Boolean) {
    _isVerboseLog = enabled
    if (enabled) LogcatMonitor.startVerbose() else LogcatMonitor.stopVerbose()
    ConfigCache.updateModulePref("lspd", 0, "config", "enable_verbose_log", enabled)
  }

  override fun getVerboseLog() =
      LogcatMonitor.getVerboseLog()?.let {
        ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
      }

  override fun getModulesLog(): ParcelFileDescriptor? {
    LogcatMonitor.checkLogFile()
    return LogcatMonitor.getModulesLog()?.let {
      ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
    }
  }

  override fun clearLogs(verbose: Boolean): Boolean {
    LogcatMonitor.refresh(verbose)
    return true
  }

  override fun getPackageInfo(packageName: String, flags: Int, uid: Int) =
      packageManager?.getPackageInfoCompat(packageName, flags, uid)

  override fun forceStopPackage(packageName: String, userId: Int) {
    activityManager?.forceStopPackage(packageName, userId)
  }

  override fun reboot() {
    powerManager?.reboot(false, null, false)
  }

  override fun uninstallPackage(packageName: String, userId: Int): Boolean {
    val latch = CountDownLatch(1)
    var result = false

    val sender =
        object : IIntentSender.Stub() {
          override fun send(
              code: Int,
              intent: Intent,
              resolvedType: String?,
              whitelistToken: IBinder?,
              finishedReceiver: android.content.IIntentReceiver?,
              requiredPermission: String?,
              options: Bundle?
          ) {
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            result = status == PackageInstaller.STATUS_SUCCESS
            latch.countDown()
          }

          override fun send(
              code: Int,
              intent: Intent,
              resolvedType: String?,
              finishedReceiver: android.content.IIntentReceiver?,
              requiredPermission: String?,
              options: Bundle?
          ): Int {
            send(code, intent, resolvedType, null, finishedReceiver, requiredPermission, options)
            return 0
          }
        }

    // Using reflection to wrap the AIDL stub into an Android IntentSender
    val intentSender =
        runCatching {
              val constructor =
                  IntentSender::class.java.getDeclaredConstructor(IIntentSender::class.java)
              constructor.isAccessible = true
              constructor.newInstance(sender)
            }
            .getOrNull() ?: return false

    val pkg = VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST)
    val flag = if (userId == -1) 0x00000002 else 0 // DELETE_ALL_USERS flag

    runCatching {
          packageManager
              ?.packageInstaller
              ?.uninstall(pkg, "android", flag, intentSender, if (userId == -1) 0 else userId)
        }
        .onFailure {
          return false
        }

    latch.await()
    return result
  }

  override fun isSepolicyLoaded() =
      android.os.SELinux.checkSELinuxAccess(
          "u:r:dex2oat:s0", "u:object_r:dex2oat_exec:s0", "file", "execute_no_trans")

  override fun getUsers(): List<UserInfo> {
    return userManager?.getRealUsers()?.map {
      UserInfo().apply {
        id = it.id
        name = it.name
      }
    } ?: emptyList()
  }

  override fun installExistingPackageAsUser(packageName: String, userId: Int): Int {
    return runCatching {
          packageManager?.installExistingPackageAsUser(packageName, userId, 0, 0, null) ?: -110
        }
        .getOrDefault(-110)
  }

  override fun systemServerRequested() = SystemServerService.systemServerRequested()

  override fun startActivityAsUserWithFeature(intent: Intent, userId: Int): Int {
    if (!intent.getBooleanExtra("lsp_no_switch_to_user", false)) {
      intent.removeExtra("lsp_no_switch_to_user")
      val currentUser = activityManager?.currentUser
      val parent = userManager?.getProfileParent(userId)?.id ?: userId
      if (currentUser != null && currentUser.id != parent) {
        if (activityManager?.switchUser(parent) == false) return -1
        val wm =
            IWindowManager.Stub.asInterface(
                android.os.ServiceManager.getService(Context.WINDOW_SERVICE))
        wm?.lockNow(null)
      }
    }
    return activityManager?.startActivityAsUserWithFeature(
        SystemContext.appThread,
        "android",
        null,
        intent,
        intent.type,
        null,
        null,
        0,
        0,
        null,
        null,
        userId) ?: -1
  }

  override fun queryIntentActivitiesAsUser(
      intent: Intent,
      flags: Int,
      userId: Int
  ): ParcelableListSlice<ResolveInfo> {
    return ParcelableListSlice(
        packageManager?.queryIntentActivitiesCompat(intent, intent.type, flags, userId)
            ?: emptyList())
  }

  override fun dex2oatFlagsLoaded() =
      SystemProperties.get("dalvik.vm.dex2oat-flags").contains("--inline-max-code-units=0")

  override fun setHiddenIcon(hide: Boolean) {
    val args =
        Bundle().apply {
          putString("value", if (hide) "0" else "1")
          putString("_user", "0")
        }
    runCatching {
          val provider =
              activityManager
                  ?.getContentProviderExternal("settings", 0, SystemContext.token, null)
                  ?.provider
          provider?.call("android", "settings", "PUT_global", "show_hidden_icon_apps_enabled", args)
        }
        .onFailure { Log.w(TAG, "setHiddenIcon failed", it) }
  }

  override fun getLogs(zipFd: ParcelFileDescriptor) {
    FileSystem.getLogs(zipFd)
  }

  override fun restartFor(intent: Intent) {} // No-op matching original

  override fun getDenyListPackages() = ConfigCache.getDenyListPackages()

  /**
   * Executes Magisk via ProcessBuilder and redirects output directly to the passed
   * ParcelFileDescriptor using the /proc/self/fd/ pseudo-filesystem.
   */
  override fun flashZip(zipPath: String, outputStream: ParcelFileDescriptor) {
    val fdFile = File("/proc/self/fd/${outputStream.fd}")
    val processBuilder =
        ProcessBuilder("magisk", "--install-module", zipPath)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(fdFile))

    runCatching {
          outputStream.use { _ ->
            FileOutputStream(fdFile, true).use { fdw ->
              val proc = processBuilder.start()
              if (proc.waitFor(10, TimeUnit.SECONDS)) {
                if (proc.exitValue() == 0) {
                  fdw.write("- Reboot after 5s\n".toByteArray())
                  Thread.sleep(5000)
                  reboot()
                } else {
                  fdw.write("! Flash failed, exit with ${proc.exitValue()}\n".toByteArray())
                }
              } else {
                proc.destroy()
                fdw.write("! Timeout, abort\n".toByteArray())
              }
            }
          }
        }
        .onFailure { Log.e(TAG, "flashZip failed", it) }
  }

  override fun clearApplicationProfileData(packageName: String) {
    packageManager?.clearApplicationProfileData(packageName)
  }

  override fun enableStatusNotification() = ConfigCache.enableStatusNotification

  override fun setEnableStatusNotification(enable: Boolean) {
    ConfigCache.enableStatusNotification = enable
    // NotificationManager.notifyStatusNotification() handled via observers later
  }

  override fun performDexOptMode(packageName: String) =
      org.matrix.vector.daemon.utils.performDexOptMode(packageName)

  override fun getDexObfuscate() = ConfigCache.isDexObfuscateEnabled()

  override fun setDexObfuscate(enabled: Boolean) = ConfigCache.setDexObfuscate(enabled)

  override fun getDex2OatWrapperCompatibility() =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.compatibility else 0

  override fun setLogWatchdog(enabled: Boolean) = ConfigCache.setLogWatchdog(enabled)

  override fun isLogWatchdogEnabled() = ConfigCache.isLogWatchdogEnabled()

  override fun setAutoInclude(packageName: String, enabled: Boolean) =
      ConfigCache.setAutoInclude(packageName, enabled)

  override fun getAutoInclude(packageName: String) = ConfigCache.getAutoInclude(packageName)
}
