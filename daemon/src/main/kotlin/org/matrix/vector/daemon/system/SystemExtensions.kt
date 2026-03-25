package org.matrix.vector.daemon.system

import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

private const val TAG = "VectorSystem"
const val PER_USER_RANGE = 100000
const val MATCH_ANY_USER = 0x00400000 // PackageManager.MATCH_ANY_USER
const val MATCH_ALL_FLAGS =
    PackageManager.MATCH_DISABLED_COMPONENTS or
        PackageManager.MATCH_DIRECT_BOOT_AWARE or
        PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
        PackageManager.MATCH_UNINSTALLED_PACKAGES or
        MATCH_ANY_USER

/** Safely fetches PackageInfo, handling API level differences. */
fun IPackageManager.getPackageInfoCompat(
    packageName: String,
    flags: Int,
    userId: Int
): PackageInfo? {
  return try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getPackageInfo(packageName, flags.toLong(), userId)
    } else {
      getPackageInfo(packageName, flags, userId)
    }
  } catch (e: Exception) {
    null
  }
}

/**
 * Fetches PackageInfo alongside its components (Activities, Services, Receivers, Providers).
 * Includes a fallback mechanism to prevent TransactionTooLargeException on massive apps.
 */
fun IPackageManager.getPackageInfoWithComponents(
    packageName: String,
    flags: Int,
    userId: Int
): PackageInfo? {
  val fullFlags =
      flags or
          PackageManager.GET_ACTIVITIES or
          PackageManager.GET_SERVICES or
          PackageManager.GET_RECEIVERS or
          PackageManager.GET_PROVIDERS

  // Fast path: Try fetching everything at once
  getPackageInfoCompat(packageName, fullFlags, userId)?.let {
    return it
  }

  // Fallback path: Fetch sequentially to avoid Binder Transaction limits
  val baseInfo = getPackageInfoCompat(packageName, flags, userId) ?: return null

  runCatching {
    baseInfo.activities =
        getPackageInfoCompat(packageName, flags or PackageManager.GET_ACTIVITIES, userId)
            ?.activities
  }
  runCatching {
    baseInfo.services =
        getPackageInfoCompat(packageName, flags or PackageManager.GET_SERVICES, userId)?.services
  }
  runCatching {
    baseInfo.receivers =
        getPackageInfoCompat(packageName, flags or PackageManager.GET_RECEIVERS, userId)?.receivers
  }
  runCatching {
    baseInfo.providers =
        getPackageInfoCompat(packageName, flags or PackageManager.GET_PROVIDERS, userId)?.providers
  }

  return baseInfo
}

/** Extracts all unique process names associated with a package's components. */
fun PackageInfo.fetchProcesses(): Set<String> {
  val processNames = mutableSetOf<String>()

  val componentArrays = arrayOf(activities, receivers, providers)
  for (components in componentArrays) {
    components?.forEach { processNames.add(it.processName) }
  }

  services?.forEach { service ->
    // Ignore isolated processes as they shouldn't be hooked in the same way
    if ((service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
      processNames.add(service.processName)
    }
  }

  return processNames
}
