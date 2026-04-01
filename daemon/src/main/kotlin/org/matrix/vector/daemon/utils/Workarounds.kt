package org.matrix.vector.daemon.utils

import android.app.IServiceConnection
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IUserManager
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ClassNotFoundException
import java.lang.reflect.Method
import org.matrix.vector.daemon.system.*

private const val TAG = "VectorWorkarounds"
private val isLenovo = Build.MANUFACTURER.equals("lenovo", ignoreCase = true)
private val isXiaomi = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)

fun IUserManager.getRealUsers(): List<UserInfo> {
  val users =
      runCatching { getUsers(true, true, true) }
          .recoverCatching { t -> if (t is NoSuchMethodError) getUsers(true) else throw t }
          .onFailure { Log.e(TAG, "All user retrieval attempts failed", it) }
          .getOrDefault(emptyList())
          .toMutableList()

  if (isLenovo) {
    val existingIds = users.map { it.id }.toSet()
    for (i in 900..909) {
      if (i !in existingIds) {
        runCatching { getUserInfo(i) }
            .onFailure { Log.e(TAG, "Failed to apply Lenovo's app cloning workaround", it) }
            .getOrNull()
            ?.let { users.add(it) }
      }
    }
  }
  return users
}

/** Android 16 DP1 SystemUI FeatureFlag and Notification Builder workaround. */
fun applyNotificationWorkaround() {
  if (Build.VERSION.SDK_INT == 36) {
    runCatching {
          val feature = Class.forName("android.app.FeatureFlagsImpl")
          val field = feature.getDeclaredField("systemui_is_cached").apply { isAccessible = true }
          field.set(null, true)
        }
        .onFailure {
          if (it !is ClassNotFoundException)
              Log.e(TAG, "Failed to bypass systemui_is_cached flag", it)
        }
  }

  runCatching { Notification.Builder(FakeContext(), "notification_workaround").build() }
      .onFailure {
        if (it is AbstractMethodError) {
          FakeContext.nullProvider = !FakeContext.nullProvider
        } else {
          Log.e(TAG, "Failed to build dummy notification", it)
        }
      }
}

fun applyPermissionManagerWorkaround() {
  try {
    val sCacheField = ServiceManager::class.java.getDeclaredField("sCache")
    sCacheField.isAccessible = true

    @Suppress("UNCHECKED_CAST") val sCache = sCacheField.get(null) as MutableMap<String, IBinder>

    // Inject proxies for the services that AMS checks during broadcast validation
    sCache["permissionmgr"] = BinderProxy("permissionmgr")
    sCache["legacy_permission"] = BinderProxy("legacy_permission")
    sCache["appops"] = BinderProxy("appops")

    Log.d(TAG, "Permission manager workarounds applied successfully")
  } catch (e: Throwable) {
    Log.e(TAG, "Failed to init permission manager workaround", e)
  }
}

private class BinderProxy(private val serviceName: String) : Binder() {
  private var mReal: IBinder? = null

  companion object {
    private val rawGetService: Method? by lazy {
      try {
        ServiceManager::class.java.getDeclaredMethod("rawGetService", String::class.java).apply {
          isAccessible = true
        }
      } catch (e: Exception) {
        Log.e(TAG, "Could not find rawGetService", e)
        null
      }
    }
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    synchronized(this) {
      if (mReal == null) {
        try {
          mReal = rawGetService?.invoke(null, serviceName) as? IBinder
        } catch (ignored: Exception) {}
      }

      val real = mReal
      if (real != null) {
        return real.transact(code, data, reply, flags)
      }
    }

    // Fallback logic if the real service cannot be reached
    // This prevents the calling process from crashing/hanging
    if (reply != null && serviceName == "permissionmgr") {
      // If the system calls getSplitPermissions(), return an empty list
      // This is a common call during broadcast permission checks
      reply.writeTypedList(emptyList<android.content.pm.PermissionInfo>())
    }
    return true
  }
}

/**
 * UpsideDownCake (Android 14) requires executing a shell command for dexopt, whereas older versions
 * use reflection/IPC.
 */
fun performDexOptMode(packageName: String): Boolean {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    return runCatching {
          val process =
              Runtime.getRuntime().exec("cmd package compile -m speed-profile -f $packageName")
          val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
          val exitCode = process.waitFor()
          exitCode == 0 && output.contains("Success")
        }
        .onFailure { Log.e(TAG, "Failed to exectute dexopt via cmd", it) }
        .getOrDefault(false)
  } else {
    return runCatching {
          packageManager?.performDexOptMode(
              packageName,
              false, // useJitProfiles
              "speed-profile",
              true,
              true,
              null) == true
        }
        .onFailure { Log.e(TAG, "Failed to invoke IPackageManager.performDexOptMode", it) }
        .getOrDefault(false)
  }
}

fun applyXspaceWorkaround(connection: IServiceConnection) {
  if (isXiaomi) {
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

fun applySqliteHelperWorkaround() {
  // OnePlus compare current package with BenchAppList to decide sync mode
  runCatching {
        val globalClass = Class.forName("android.database.sqlite.SQLiteGlobal")
        val syncModeField = globalClass.getDeclaredField("sDefaultSyncMode")
        syncModeField.isAccessible = true

        // Prevents from calling getPkgs()
        if (syncModeField.get(null) == null) {
          syncModeField.set(null, "NORMAL")
          Log.i(TAG, "SQLiteGlobal.sDefaultSyncMode initialized to NORMAL.")
        }
      }
      .onFailure { Log.v(TAG, "SQLiteGlobal workaround not applied: ${it.message}") }

  // Fix AOSP Settings.Global dependency (API 28+ but not recent Android versions)
  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
    runCatching {
          val walClass = Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags")

          // Mark as initialized so initIfNeeded() returns immediately
          walClass.getDeclaredField("sInitialized").apply {
            isAccessible = true
            set(null, true)
          }

          // Mark as 'Currently Calling' as a secondary recursion guard
          walClass.getDeclaredField("sCallingGlobalSettings").apply {
            isAccessible = true
            set(null, true)
          }
          Log.i(TAG, "SQLiteCompatibilityWalFlags successfully bypassed.")
        }
        .onFailure { Log.v(TAG, "Could not disable SQLiteCompatibilityWalFlags: ${it.message}") }
  }
}
