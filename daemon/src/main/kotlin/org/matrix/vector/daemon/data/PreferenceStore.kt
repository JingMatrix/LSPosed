package org.matrix.vector.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

object PreferenceStore {

  fun getModulePrefs(packageName: String, userId: Int, group: String): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    ConfigCache.dbHelper.readableDatabase
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
    val db = ConfigCache.dbHelper.writableDatabase
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
          db.insertWithOnConflict("configs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
    ConfigCache.dbHelper.writableDatabase.delete(
        "configs",
        "module_pkg_name=? AND user_id=? AND `group`=?",
        arrayOf(moduleName, userId.toString(), group))
  }

  fun isDexObfuscateEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_dex_obfuscate"] as? Boolean ?: true

  fun setDexObfuscate(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_dex_obfuscate", enabled)

  fun isLogWatchdogEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_log_watchdog"] as? Boolean ?: true

  fun setLogWatchdog(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_log_watchdog", enabled)

  fun isScopeRequestBlocked(pkg: String): Boolean =
      (getModulePrefs("lspd", 0, "config")["scope_request_blocked"] as? Set<*>)?.contains(pkg) ==
          true
}
