package org.matrix.vector.daemon.data

import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageParser
import android.system.Os
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.ipc.InjectedModuleService
import org.matrix.vector.daemon.system.MATCH_ALL_FLAGS
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.fetchProcesses
import org.matrix.vector.daemon.system.getPackageInfoWithComponents
import org.matrix.vector.daemon.system.packageManager
import org.matrix.vector.daemon.system.userManager
import org.matrix.vector.daemon.utils.getRealUsers

private const val TAG = "VectorConfigCache"

data class ProcessScope(val processName: String, val uid: Int)

object ConfigCache {
  @Volatile var api: String = "(???)"
  @Volatile var enableStatusNotification = true

  val dbHelper = Database()

  // Thread-safe maps for IPC readers
  val cachedModules = ConcurrentHashMap<String, Module>()
  val cachedScopes = ConcurrentHashMap<ProcessScope, MutableList<Module>>()

  // Coroutine Scope for background DB tasks
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // A conflated channel automatically drops older pending events if a new one arrives.
  // This perfectly replaces the manual `lastModuleCacheTime` timestamp logic!
  private val cacheUpdateChannel = Channel<Unit>(Channel.CONFLATED)

  init {
    // Start the background consumer
    scope.launch {
      for (request in cacheUpdateChannel) {
        performCacheUpdate()
      }
    }
  }

  /**
   * Triggers an asynchronous cache update. Multiple rapid calls are naturally coalesced by the
   * Conflated Channel.
   */
  fun requestCacheUpdate() {
    cacheUpdateChannel.trySend(Unit)
  }

  /** Blocks and forces an immediate cache update (Used during system_server boot). */
  fun forceCacheUpdateSync() {
    performCacheUpdate()
  }

  private fun performCacheUpdate() {
    if (packageManager == null) return // Wait for PM to be ready

    Log.d(TAG, "Executing Cache Update...")
    val db = dbHelper.readableDatabase

    // 1. Fetch enabled modules
    val newModules = mutableMapOf<String, Module>()
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
            val apkPath = cursor.getString(1)
            if (pkgName == "lspd") continue

            val isObfuscateEnabled = true
            val preLoadedApk = FileSystem.loadModule(apkPath, isObfuscateEnabled)

            if (preLoadedApk != null) {
              val module = Module()
              module.packageName = pkgName
              module.apkPath = apkPath
              module.file = preLoadedApk
              // Note: module.appId, module.applicationInfo, and module.service
              // will be populated in Phase 4 when we implement InjectedModuleService
              newModules[pkgName] = module
            } else {
              Log.w(TAG, "Failed to parse DEX/ZIP for $pkgName, skipping.")
            }
          }
        }

    // 2. Fetch scopes and map heavy PM logic
    val newScopes = ConcurrentHashMap<ProcessScope, MutableList<Module>>()
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

            // system_server it fetches its own modules
            if (appPkg == "system") continue

            // Ensure the module is actually valid and loaded
            val module = newModules[modPkg] ?: continue

            // Heavy logic: Fetch associated processes
            val pkgInfo =
                packageManager?.getPackageInfoWithComponents(appPkg, MATCH_ALL_FLAGS, userId)
            if (pkgInfo?.applicationInfo == null) continue

            val processNames = pkgInfo.fetchProcesses()
            if (processNames.isEmpty()) continue

            val appUid = pkgInfo.applicationInfo!!.uid

            for (processName in processNames) {
              val processScope = ProcessScope(processName, appUid)
              newScopes.getOrPut(processScope) { mutableListOf() }.add(module)

              // Always allow the module to inject itself across all users
              if (modPkg == appPkg) {
                val appId = appUid % PER_USER_RANGE
                userManager?.getRealUsers()?.forEach { user ->
                  val moduleUid = user.id * PER_USER_RANGE + appId
                  if (moduleUid != appUid) { // Skip duplicate
                    val moduleSelf = ProcessScope(processName, moduleUid)
                    newScopes.getOrPut(moduleSelf) { mutableListOf() }.add(module)
                  }
                }
              }
            }
          }
        }

    // 3. Atomically swap the memory cache
    cachedModules.clear()
    cachedModules.putAll(newModules)

    cachedScopes.clear()
    cachedScopes.putAll(newScopes)

    Log.d(TAG, "Cache Update Complete. Modules: ${cachedModules.size}")
  }

  fun getModulesForProcess(processName: String, uid: Int): List<Module> {
    return cachedScopes[ProcessScope(processName, uid)] ?: emptyList()
  }

  // --- Preferences & Settings ---
  fun isDexObfuscateEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_dex_obfuscate"] as? Boolean ?: true

  fun setDexObfuscate(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_dex_obfuscate", enabled)

  fun isLogWatchdogEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_log_watchdog"] as? Boolean ?: true

  fun setLogWatchdog(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_log_watchdog", enabled)

  // --- Modules & Scope DB Operations ---
  fun getEnabledModules(): List<String> = cachedModules.keys.toList()

  fun enableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val values = ContentValues().apply { put("enabled", 1) }
    val changed =
        dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (changed) requestCacheUpdate()
    return changed
  }

  fun disableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val values = ContentValues().apply { put("enabled", 0) }
    val changed =
        dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (changed) requestCacheUpdate()
    return changed
  }

  fun getModuleScope(packageName: String): MutableList<Application>? {
    if (packageName == "lspd") return null
    val result = mutableListOf<Application>()
    dbHelper.readableDatabase
        .query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("app_pkg_name", "user_id"),
            "modules.module_pkg_name = ?",
            arrayOf(packageName),
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            result.add(
                Application().apply {
                  this.packageName = cursor.getString(0)
                  this.userId = cursor.getInt(1)
                })
          }
        }
    return result
  }

  fun setModuleScope(packageName: String, scope: MutableList<Application>): Boolean {
    enableModule(packageName)
    val db = dbHelper.writableDatabase
    db.beginTransaction()
    try {
      val mid =
          db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
              .apply { bindString(1, packageName) }
              .simpleQueryForLong()
      db.delete("scope", "mid = ?", arrayOf(mid.toString()))

      val values = ContentValues().apply { put("mid", mid) }
      for (app in scope) {
        if (app.packageName == "system" && app.userId != 0) continue
        values.put("app_pkg_name", app.packageName)
        values.put("user_id", app.userId)
        db.insertWithOnConflict(
            "scope", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
      }
      db.setTransactionSuccessful()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set scope", e)
      return false
    } finally {
      db.endTransaction()
    }
    requestCacheUpdate()
    return true
  }

  // --- Configs Table Operations ---
  fun getModulePrefs(packageName: String, userId: Int, group: String): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    dbHelper.readableDatabase
        .query(
            "configs",
            arrayOf("`key`", "data"),
            "module_pkg_name = ? AND user_id = ? AND `group` = ?",
            arrayOf(packageName, userId.toString(), group),
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val key = cursor.getString(0)
            val blob = cursor.getBlob(1)
            val obj = org.apache.commons.lang3.SerializationUtilsX.deserialize<Any>(blob)
            if (obj != null) result[key] = obj
          }
        }
    return result
  }

  fun updateModulePref(moduleName: String, userId: Int, group: String, key: String, value: Any?) {
    updateModulePrefs(moduleName, userId, group, mapOf(key to value))
  }

  fun updateModulePrefs(moduleName: String, userId: Int, group: String, diff: Map<String, Any?>) {
    val db = dbHelper.writableDatabase
    db.beginTransaction()
    try {
      for ((key, value) in diff) {
        if (value is java.io.Serializable) {
          val values =
              ContentValues().apply {
                put("`group`", group)
                put("`key`", key)
                put("data", org.apache.commons.lang3.SerializationUtilsX.serialize(value))
                put("module_pkg_name", moduleName)
                put("user_id", userId.toString())
              }
          db.insertWithOnConflict(
              "configs", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } else {
          db.delete(
              "configs",
              "module_pkg_name=? AND user_id=? AND `group`=? AND `key`=?",
              arrayOf(moduleName, userId.toString(), group, key))
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun deleteModulePrefs(moduleName: String, userId: Int, group: String) {
    dbHelper.writableDatabase.delete(
        "configs",
        "module_pkg_name=? AND user_id=? AND `group`=?",
        arrayOf(moduleName, userId.toString(), group))
  }

  // --- Helpers ---
  fun isManager(uid: Int): Boolean = uid == BuildConfig.MANAGER_INJECTED_UID

  fun getModuleByUid(uid: Int): Module? =
      cachedModules.values.firstOrNull { it.appId == uid % PER_USER_RANGE }

  fun isScopeRequestBlocked(pkg: String): Boolean =
      (getModulePrefs("lspd", 0, "config")["scope_request_blocked"] as? Set<*>)?.contains(pkg) ==
          true

  fun getDenyListPackages(): List<String> =
      emptyList() // Needs Magisk DB parsing logic if Api == "Zygisk"

  fun getAutoInclude(pkg: String): Boolean = false // Query modules table for auto_include flag

  fun setAutoInclude(pkg: String, enabled: Boolean): Boolean = false

  fun getModulesForSystemServer(): List<Module> {
    val modules = mutableListOf<Module>()

    // system_server must have specific SELinux execmem capabilities to hook properly
    if (!android.os.SELinux.checkSELinuxAccess(
        "u:r:system_server:s0", "u:r:system_server:s0", "process", "execmem")) {
      Log.e(TAG, "Skipping system_server injection: sepolicy execmem denied")
      return modules
    }

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

            // Reuse memory cache if available
            val cached = cachedModules[pkgName]
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

            // Parse the APK locally to simulate ApplicationInfo without ActivityManager running
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

            FileSystem.loadModule(apkPath, isDexObfuscateEnabled())?.let {
              module.file = it
              cachedModules.putIfAbsent(pkgName, module)
              modules.add(module)
            }
          }
        }
    return modules
  }

  fun getPrefsPath(packageName: String, uid: Int): String {
    val userId = uid / PER_USER_RANGE
    val path =
        FileSystem.basePath.resolve(
            "misc/prefs${if (userId == 0) "" else userId.toString()}/$packageName")
    // Apply Os.chown to path here
    return path.toString()
  }

  fun removeModuleScope(packageName: String, scopePackageName: String, userId: Int): Boolean {
    if (packageName == "lspd" || (scopePackageName == "system" && userId != 0)) return false
    val db = dbHelper.writableDatabase
    val mid =
        db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
            .apply { bindString(1, packageName) }
            .simpleQueryForLong()
    db.delete(
        "scope",
        "mid = ? AND app_pkg_name = ? AND user_id = ?",
        arrayOf(mid.toString(), scopePackageName, userId.toString()))
    requestCacheUpdate()
    return true
  }

  fun updateModuleApkPath(packageName: String, apkPath: String?, force: Boolean): Boolean {
    if (apkPath == null || packageName == "lspd") return false
    val values =
        ContentValues().apply {
          put("module_pkg_name", packageName)
          put("apk_path", apkPath)
        }
    val db = dbHelper.writableDatabase
    var count =
        db.insertWithOnConflict(
                "modules", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            .toInt()
    if (count < 0) {
      val cached = cachedModules[packageName]
      if (force || cached == null || cached.apkPath != apkPath) {
        count =
            db.updateWithOnConflict(
                "modules",
                values,
                "module_pkg_name=?",
                arrayOf(packageName),
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
      } else count = 0
    }
    if (!force && count > 0) requestCacheUpdate()
    return count > 0
  }

  fun removeModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val res =
        dbHelper.writableDatabase.delete("modules", "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (res) requestCacheUpdate()
    return res
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
    return !cachedScopes.containsKey(scope) && !isManager(scope.uid)
  }
}
