package org.matrix.vector.daemon.utils

import android.app.Notification
import android.content.pm.UserInfo
import android.os.Build
import android.os.IUserManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ClassNotFoundException
import org.matrix.vector.daemon.system.packageManager

private const val TAG = "VectorWorkarounds"
private val isLenovo = Build.MANUFACTURER.equals("lenovo", ignoreCase = true)

/** Retrieves all users, applying Lenovo's app cloning workaround (hides users 900-909). */
fun IUserManager.getRealUsers(): List<UserInfo> {
  val users =
      runCatching { getUsers(true, true, true) }
          .getOrElse {
            getUsers(true) // Fallback for older Android versions
          }
          ?.toMutableList() ?: mutableListOf()

  if (isLenovo) {
    val existingIds = users.map { it.id }.toSet()
    for (i in 900..909) {
      if (i !in existingIds) {
        runCatching { getUserInfo(i) }.getOrNull()?.let { users.add(it) }
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
        .getOrDefault(false)
  }
}
