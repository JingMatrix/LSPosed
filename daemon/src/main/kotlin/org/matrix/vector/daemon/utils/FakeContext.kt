package org.matrix.vector.daemon.utils

import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Build
import org.matrix.vector.daemon.system.packageManager as sysPackageManager

/**
 * A stub context used by the daemon to forge intents and notifications without triggering
 * system_server strict mode violations.
 */
class FakeContext(private val fakePackageName: String = "android") : ContextWrapper(null) {

  companion object {
    @Volatile var nullProvider = false
    private var systemAppInfo: ApplicationInfo? = null
    private var fakeTheme: Resources.Theme? = null
  }

  override fun getPackageName(): String = fakePackageName

  override fun getOpPackageName(): String = "android"

  override fun getApplicationInfo(): ApplicationInfo {
    if (systemAppInfo == null) {
      systemAppInfo =
          runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  sysPackageManager?.getApplicationInfo("android", 0L, 0)
                } else {
                  sysPackageManager?.getApplicationInfo("android", 0, 0)
                }
              }
              .getOrNull()
    }
    return systemAppInfo ?: ApplicationInfo()
  }

  override fun getContentResolver(): ContentResolver? {
    return if (nullProvider) null else object : ContentResolver(this) {}
  }

  override fun getTheme(): Resources.Theme {
    if (fakeTheme == null) fakeTheme = resources.newTheme()
    return fakeTheme!!
  }

  // Resources fetching will be implemented in Phase 2 (FileSystem/Config)
  override fun getResources(): Resources {
    throw NotImplementedError("Resources will be provided by FileManager in Phase 2")
  }

  // Required for Android 12+
  override fun getAttributionTag(): String? = null
}
