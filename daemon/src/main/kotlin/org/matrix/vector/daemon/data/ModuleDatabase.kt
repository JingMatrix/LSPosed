package org.matrix.vector.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.lsposed.lspd.models.Application

private const val TAG = "VectorModuleDb"

object ModuleDatabase {

  fun enableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val values = ContentValues().apply { put("enabled", 1) }
    val changed =
        ConfigCache.dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (changed) ConfigCache.requestCacheUpdate()
    return changed
  }

  fun disableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val values = ContentValues().apply { put("enabled", 0) }
    val changed =
        ConfigCache.dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (changed) ConfigCache.requestCacheUpdate()
    return changed
  }

  fun getModuleScope(packageName: String): MutableList<Application>? {
    if (packageName == "lspd") return null
    val result = mutableListOf<Application>()
    ConfigCache.dbHelper.readableDatabase
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
    val db = ConfigCache.dbHelper.writableDatabase
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
        db.insertWithOnConflict("scope", null, values, SQLiteDatabase.CONFLICT_IGNORE)
      }
      db.setTransactionSuccessful()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set scope", e)
      return false
    } finally {
      db.endTransaction()
    }
    ConfigCache.requestCacheUpdate()
    return true
  }

  fun removeModuleScope(packageName: String, scopePackageName: String, userId: Int): Boolean {
    if (packageName == "lspd" || (scopePackageName == "system" && userId != 0)) return false
    val db = ConfigCache.dbHelper.writableDatabase
    val mid =
        db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
            .apply { bindString(1, packageName) }
            .simpleQueryForLong()
    db.delete(
        "scope",
        "mid = ? AND app_pkg_name = ? AND user_id = ?",
        arrayOf(mid.toString(), scopePackageName, userId.toString()))
    ConfigCache.requestCacheUpdate()
    return true
  }

  fun updateModuleApkPath(packageName: String, apkPath: String?, force: Boolean): Boolean {
    if (apkPath == null || packageName == "lspd") return false
    val values =
        ContentValues().apply {
          put("module_pkg_name", packageName)
          put("apk_path", apkPath)
        }
    val db = ConfigCache.dbHelper.writableDatabase
    var count =
        db.insertWithOnConflict("modules", null, values, SQLiteDatabase.CONFLICT_IGNORE).toInt()

    if (count < 0) {
      val cached = ConfigCache.state.modules[packageName]
      if (force || cached == null || cached.apkPath != apkPath) {
        count =
            db.updateWithOnConflict(
                "modules",
                values,
                "module_pkg_name=?",
                arrayOf(packageName),
                SQLiteDatabase.CONFLICT_IGNORE)
      } else count = 0
    }
    if (!force && count > 0) ConfigCache.requestCacheUpdate()
    return count > 0
  }

  fun removeModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val res =
        ConfigCache.dbHelper.writableDatabase.delete(
            "modules", "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (res) ConfigCache.requestCacheUpdate()
    return res
  }

  fun getAutoInclude(packageName: String): Boolean {
    if (packageName == "lspd") return false

    var isAutoInclude = false
    ConfigCache.dbHelper.readableDatabase
        .query(
            "modules",
            arrayOf("auto_include"),
            "module_pkg_name = ?",
            arrayOf(packageName),
            null,
            null,
            null)
        .use { cursor ->
          if (cursor.moveToFirst()) {
            isAutoInclude = cursor.getInt(0) == 1
          }
        }
    return isAutoInclude
  }

  fun setAutoInclude(packageName: String, enabled: Boolean): Boolean {
    if (packageName == "lspd") return false

    val values = ContentValues().apply { put("auto_include", if (enabled) 1 else 0) }

    val changed =
        ConfigCache.dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0

    // If the auto_include flag changes, we should rebuild the scope cache
    if (changed) {
      ConfigCache.requestCacheUpdate()
    }

    return changed
  }
}
