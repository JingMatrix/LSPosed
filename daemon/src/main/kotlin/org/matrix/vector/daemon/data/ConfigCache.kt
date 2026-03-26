package org.matrix.vector.daemon.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageParser
import android.system.Os
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.ipc.InjectedModuleService
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.system.*
import org.matrix.vector.daemon.utils.getRealUsers

private const val TAG = "VectorConfigCache"

object ConfigCache {

  // --- IMMUTABLE STATE ---
  @Volatile
  var state = DaemonState()
    private set

  val dbHelper = Database() // Kept public for PreferenceStore and ModuleDatabase

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val cacheUpdateChannel = Channel<Unit>(Channel.CONFLATED)

  init {
    scope.launch {
      for (request in cacheUpdateChannel) {
        performCacheUpdate()
      }
    }
    initializeConfig()
  }

  // --- STATE PROXIES (For backwards compatibility) ---
  var api: String
    get() = state.api
    set(value) {
      state = state.copy(api = value)
    }

  var enableStatusNotification: Boolean
    get() = state.enableStatusNotification
    set(value) {
      state = state.copy(enableStatusNotification = value)
    }

  val cachedModules: Map<String, Module>
    get() = state.modules

  val cachedScopes: Map<ProcessScope, List<Module>>
    get() = state.scopes

  private fun initializeConfig() {
    val config = PreferenceStore.getModulePrefs("lspd", 0, "config")

    ManagerService.isVerboseLog = config["enable_verbose_log"] as? Boolean ?: true
    val enableStatusNotif = config["enable_status_notification"] as? Boolean ?: true

    if (config["enable_auto_add_shortcut"] != null) {
      PreferenceStore.updateModulePref("lspd", 0, "config", "enable_auto_add_shortcut", null)
    }

    val pathStr = config["misc_path"] as? String
    val miscPath =
        if (pathStr == null) {
          val newPath = Paths.get("/data/misc", UUID.randomUUID().toString())
          PreferenceStore.updateModulePref("lspd", 0, "config", "misc_path", newPath.toString())
          newPath
        } else {
          Paths.get(pathStr)
        }

    runCatching {
          val perms =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x"))
          Files.createDirectories(miscPath, perms)
          FileSystem.setSelinuxContextRecursive(miscPath, "u:object_r:xposed_data:s0")
        }
        .onFailure { Log.e(TAG, "Failed to create misc directory", it) }

    // Swap state with initialization data
    state = state.copy(enableStatusNotification = enableStatusNotif, miscPath = miscPath)
  }

  private fun ensureCacheReady() {
    val currentState = state
    if (!currentState.isCacheReady && packageManager?.asBinder()?.isBinderAlive == true) {
      synchronized(this) {
        if (!state.isCacheReady) {
          Log.i(TAG, "System services are ready. Mapping modules and scopes.")
          updateManager(false)
          forceCacheUpdateSync()
          state = state.copy(isCacheReady = true)
        }
      }
    }
  }

  fun updateManager(uninstalled: Boolean) {
    if (uninstalled) {
      state = state.copy(managerUid = -1)
      return
    }
    if (packageManager?.asBinder()?.isBinderAlive == true) {
      runCatching {
            val info =
                packageManager?.getPackageInfoCompat(BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME, 0, 0)
            val uid = info?.applicationInfo?.uid ?: -1
            if (uid == -1) Log.i(TAG, "Manager is not installed")
            state = state.copy(managerUid = uid)
          }
          .onFailure { state = state.copy(managerUid = -1) }
    }
  }

  fun isManager(uid: Int): Boolean {
    ensureCacheReady()
    return uid == state.managerUid || uid == BuildConfig.MANAGER_INJECTED_UID
  }

  fun requestCacheUpdate() {
    cacheUpdateChannel.trySend(Unit)
  }

  fun forceCacheUpdateSync() {
    performCacheUpdate()
  }

  /** Builds a completely new Immutable State and atomically swaps it. */
  private fun performCacheUpdate() {
    if (packageManager == null) return

    Log.d(TAG, "Executing Cache Update...")
    val db = dbHelper.readableDatabase
    val oldState = state

    val newModules = mutableMapOf<String, Module>()
    val obsoleteModules = mutableSetOf<String>()
    val obsoletePaths = mutableMapOf<String, String>()

    db.query(
            "modules",
            arrayOf("module_pkg_name", "apk_path"),
            "enabled = 1",
            null,
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val pkgName = cursor.getString(0)
            var apkPath = cursor.getString(1)
            if (pkgName == "lspd") continue

            val oldModule = oldState.modules[pkgName]

            var pkgInfo: android.content.pm.PackageInfo? = null
            val users = userManager?.getRealUsers() ?: emptyList()
            for (user in users) {
              pkgInfo = packageManager?.getPackageInfoCompat(pkgName, MATCH_ALL_FLAGS, user.id)
              if (pkgInfo?.applicationInfo != null) break
            }

            if (pkgInfo?.applicationInfo == null) {
              Log.w(TAG, "Failed to find package info of $pkgName")
              obsoleteModules.add(pkgName)
              continue
            }

            val appInfo = pkgInfo.applicationInfo

            if (oldModule != null &&
                appInfo?.sourceDir != null &&
                apkPath != null &&
                oldModule.apkPath != null &&
                FileSystem.toGlobalNamespace(apkPath).exists() &&
                apkPath == oldModule.apkPath &&
                File(appInfo.sourceDir).parent == File(apkPath).parent) {

              if (oldModule.appId == -1) oldModule.applicationInfo = appInfo
              newModules[pkgName] = oldModule
              continue
            }

            val realApkPath = getModuleApkPath(appInfo!!)
            if (realApkPath == null) {
              Log.w(TAG, "Failed to find path of $pkgName")
              obsoleteModules.add(pkgName)
              continue
            } else {
              apkPath = realApkPath
              obsoletePaths[pkgName] = realApkPath
            }

            val preLoadedApk =
                FileSystem.loadModule(apkPath, PreferenceStore.isDexObfuscateEnabled())
            if (preLoadedApk != null) {
              val module =
                  Module().apply {
                    packageName = pkgName
                    this.apkPath = apkPath
                    appId = appInfo.uid
                    applicationInfo = appInfo
                    service = oldModule?.service ?: InjectedModuleService(pkgName)
                    file = preLoadedApk
                  }
              newModules[pkgName] = module
            } else {
              Log.w(TAG, "Failed to parse DEX/ZIP for $pkgName, skipping.")
              obsoleteModules.add(pkgName)
            }
          }
        }

    if (packageManager?.asBinder()?.isBinderAlive == true) {
      obsoleteModules.forEach { ModuleDatabase.removeModule(it) }
      obsoletePaths.forEach { (pkg, path) -> ModuleDatabase.updateModuleApkPath(pkg, path, true) }
    }

    val newScopes = mutableMapOf<ProcessScope, MutableList<Module>>()
    db.query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("app_pkg_name", "module_pkg_name", "user_id"),
            "enabled = 1",
            null,
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val appPkg = cursor.getString(0)
            val modPkg = cursor.getString(1)
            val userId = cursor.getInt(2)

            if (appPkg == "system") continue

            val module = newModules[modPkg] ?: continue
            val pkgInfo =
                packageManager?.getPackageInfoWithComponents(appPkg, MATCH_ALL_FLAGS, userId)
            if (pkgInfo?.applicationInfo == null) continue

            val processNames = pkgInfo.fetchProcesses()
            if (processNames.isEmpty()) continue

            val appUid = pkgInfo.applicationInfo!!.uid

            for (processName in processNames) {
              val processScope = ProcessScope(processName, appUid)
              newScopes.getOrPut(processScope) { mutableListOf() }.add(module)

              if (modPkg == appPkg) {
                val appId = appUid % PER_USER_RANGE
                userManager?.getRealUsers()?.forEach { user ->
                  val moduleUid = user.id * PER_USER_RANGE + appId
                  if (moduleUid != appUid) {
                    val moduleSelf = ProcessScope(processName, moduleUid)
                    newScopes.getOrPut(moduleSelf) { mutableListOf() }.add(module)
                  }
                }
              }
            }
          }
        }

    // --- ATOMIC STATE SWAP ---
    state = oldState.copy(modules = newModules, scopes = newScopes)

    Log.d(TAG, "Cache Update Complete. Map Swap successful.")
  }

  fun getModulesForProcess(processName: String, uid: Int): List<Module> {
    ensureCacheReady()
    return state.scopes[ProcessScope(processName, uid)] ?: emptyList()
  }

  fun getModuleByUid(uid: Int): Module? =
      state.modules.values.firstOrNull { it.appId == uid % PER_USER_RANGE }

  fun getModulesForSystemServer(): List<Module> {
    val modules = mutableListOf<Module>()
    if (!android.os.SELinux.checkSELinuxAccess(
        "u:r:system_server:s0", "u:r:system_server:s0", "process", "execmem")) {
      Log.e(TAG, "Skipping system_server injection: sepolicy execmem denied")
      return modules
    }

    val currentState = state

    dbHelper.readableDatabase
        .query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("module_pkg_name", "apk_path"),
            "app_pkg_name=? AND enabled=1",
            arrayOf("system"),
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val pkgName = cursor.getString(0)
            val apkPath = cursor.getString(1)

            val cached = currentState.modules[pkgName]
            if (cached != null) {
              modules.add(cached)
              continue
            }

            val statPath = FileSystem.toGlobalNamespace("/data/user_de/0/$pkgName").absolutePath
            val module =
                Module().apply {
                  packageName = pkgName
                  this.apkPath = apkPath
                  appId = runCatching { Os.stat(statPath).st_uid }.getOrDefault(-1)
                  service = InjectedModuleService(pkgName)
                }

            runCatching {
                  @Suppress("DEPRECATION")
                  val pkg = PackageParser().parsePackage(File(apkPath), 0, false)
                  module.applicationInfo =
                      pkg.applicationInfo.apply {
                        sourceDir = apkPath
                        dataDir = statPath
                        deviceProtectedDataDir = statPath
                        HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(this, statPath)
                        processName = pkgName
                      }
                }
                .onFailure { Log.w(TAG, "Failed to parse $apkPath", it) }

            FileSystem.loadModule(apkPath, PreferenceStore.isDexObfuscateEnabled())?.let {
              module.file = it
              modules.add(module)
              // We intentionally don't mutate state.modules here. Cache update will catch it.
            }
          }
        }
    return modules
  }

  fun getPrefsPath(packageName: String, uid: Int): String {
    ensureCacheReady()
    val currentState = state
    val basePath =
        currentState.miscPath ?: throw IllegalStateException("Fatal: miscPath not initialized!")

    val userId = uid / PER_USER_RANGE
    val userSuffix = if (userId == 0) "" else userId.toString()
    val path = basePath.resolve("prefs$userSuffix").resolve(packageName)

    val module = currentState.modules[packageName]
    if (module != null && module.appId == uid % PER_USER_RANGE) {
      runCatching {
            val perms =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x"))
            Files.createDirectories(path, perms)
            Files.walk(path).forEach { p -> Os.chown(p.toString(), uid, uid) }
          }
          .onFailure { Log.e(TAG, "Failed to prepare prefs path", it) }
    }
    return path.toString()
  }

  fun getModuleApkPath(info: ApplicationInfo): String? {
    val apks = mutableListOf<String>()
    info.sourceDir?.let { apks.add(it) }
    info.splitSourceDirs?.let { apks.addAll(it) }

    return apks.firstOrNull { apk ->
      runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
              zip.getEntry("META-INF/xposed/java_init.list") != null ||
                  zip.getEntry("assets/xposed_init") != null
            }
          }
          .getOrDefault(false)
    }
  }

  fun shouldSkipProcess(scope: ProcessScope): Boolean {
    ensureCacheReady()
    return !state.scopes.containsKey(scope) && !isManager(scope.uid)
  }

  fun getEnabledModules(): List<String> = state.modules.keys.toList()

  fun getDenyListPackages(): List<String> = emptyList()

  fun getModulePrefs(pkg: String, userId: Int, group: String) =
      PreferenceStore.getModulePrefs(pkg, userId, group)

  fun updateModulePref(pkg: String, userId: Int, group: String, key: String, value: Any?) =
      PreferenceStore.updateModulePref(pkg, userId, group, key, value)

  fun updateModulePrefs(pkg: String, userId: Int, group: String, diff: Map<String, Any?>) =
      PreferenceStore.updateModulePrefs(pkg, userId, group, diff)

  fun deleteModulePrefs(pkg: String, userId: Int, group: String) =
      PreferenceStore.deleteModulePrefs(pkg, userId, group)

  fun isDexObfuscateEnabled() = PreferenceStore.isDexObfuscateEnabled()

  fun setDexObfuscate(enabled: Boolean) = PreferenceStore.setDexObfuscate(enabled)

  fun isLogWatchdogEnabled() = PreferenceStore.isLogWatchdogEnabled()

  fun setLogWatchdog(enabled: Boolean) = PreferenceStore.setLogWatchdog(enabled)

  fun isScopeRequestBlocked(pkg: String) = PreferenceStore.isScopeRequestBlocked(pkg)

  fun enableModule(pkg: String) = ModuleDatabase.enableModule(pkg)

  fun disableModule(pkg: String) = ModuleDatabase.disableModule(pkg)

  fun getModuleScope(pkg: String) = ModuleDatabase.getModuleScope(pkg)

  fun setModuleScope(pkg: String, scope: MutableList<Application>) =
      ModuleDatabase.setModuleScope(pkg, scope)

  fun removeModuleScope(pkg: String, scopePkg: String, userId: Int) =
      ModuleDatabase.removeModuleScope(pkg, scopePkg, userId)

  fun updateModuleApkPath(pkg: String, apkPath: String?, force: Boolean) =
      ModuleDatabase.updateModuleApkPath(pkg, apkPath, force)

  fun removeModule(pkg: String) = ModuleDatabase.removeModule(pkg)

  fun getAutoInclude(pkg: String) = ModuleDatabase.getAutoInclude(pkg)

  fun setAutoInclude(pkg: String, enabled: Boolean) = ModuleDatabase.setAutoInclude(pkg, enabled)
}
