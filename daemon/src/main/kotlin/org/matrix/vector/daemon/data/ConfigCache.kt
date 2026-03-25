package org.matrix.vector.daemon.data

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.lsposed.lspd.models.Module
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

            // TODO: Fetch real obfuscate pref from configs table later
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
}
