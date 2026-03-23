package org.matrix.vector.impl.hookers

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.di.LegacyPackageInfo
import org.matrix.vector.impl.di.VectorBootstrap

/** Safe reflection helper */
private inline fun <reified T> Any.getFieldValue(name: String): T? {
    var clazz: Class<*>? = this.javaClass
    while (clazz != null) {
        try {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            return field.get(this) as? T
        } catch (ignored: NoSuchFieldException) {
            clazz = clazz.superclass
        }
    }
    return null
}

/** Tracks and prepares Application instances when their LoadedApk is instantiated. */
object LoadedApkCtorHooker : XposedInterface.Hooker {
    val trackedApks = ConcurrentHashMap.newKeySet<Any>()

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val loadedApk = chain.thisObject ?: return result

        val packageName = loadedApk.getFieldValue<String>("mPackageName") ?: return result

        VectorBootstrap.withLegacy { delegate ->
            if (!delegate.isResourceHookingDisabled) {
                val resDir = loadedApk.getFieldValue<String>("mResDir")
                delegate.setPackageNameForResDir(packageName, resDir)
            }
        }

        // Avoid OnePlus custom opt crashing
        if (
            Log.getStackTraceString(Throwable())
                .contains("android.app.ActivityThread\$ApplicationThread.schedulePreload")
        ) {
            return result
        }

        trackedApks.add(loadedApk)
        return result
    }
}

/** Triggers package loading events immediately after the Application ClassLoader is created. */
object LoadedApkCreateCLHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val loadedApk = chain.thisObject ?: return result

        // LoadedApk.createOrUpdateClassLoaderLocked(List<String> addedPaths) is called with
        // addedPaths == null when Android is creating the ClassLoader for the very first time for
        // this app.
        if (
            chain.args.firstOrNull() != null || !LoadedApkCtorHooker.trackedApks.contains(loadedApk)
        ) {
            return result
        }

        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentPkgMethod = activityThreadClass.getDeclaredMethod("currentPackageName")
            val currentProcMethod = activityThreadClass.getDeclaredMethod("currentProcessName")

            var packageName = currentPkgMethod.invoke(null) as? String
            var processName = currentProcMethod.invoke(null) as? String
            val apkPackageName = loadedApk.getFieldValue<String>("mPackageName") ?: return result

            val isFirstPackage =
                packageName != null && processName != null && packageName == apkPackageName
            if (!isFirstPackage) {
                packageName = apkPackageName
                processName = currentPkgMethod.invoke(null) as? String ?: apkPackageName
            } else if (packageName == "android") {
                packageName = "system"
            }

            val classLoader = loadedApk.getFieldValue<ClassLoader>("mClassLoader") ?: return result
            val mIncludeCode = loadedApk.getFieldValue<Boolean>("mIncludeCode") ?: true

            if (!isFirstPackage && !mIncludeCode) return result
            val appInfo =
                loadedApk.getFieldValue<ApplicationInfo>("mApplicationInfo") ?: return result

            // Dispatch Modern Lifecycle
            val defaultClassLoader =
                loadedApk.getFieldValue<ClassLoader>("mDefaultClassLoader") ?: classLoader
            VectorLifecycleManager.dispatchPackageLoaded(
                packageName,
                appInfo,
                isFirstPackage,
                defaultClassLoader,
            )

            // Dispatch Legacy Lifecycle
            VectorBootstrap.withLegacy { delegate ->
                delegate.onPackageLoaded(
                    LegacyPackageInfo(
                        packageName,
                        processName,
                        classLoader,
                        appInfo,
                        isFirstPackage,
                    )
                )
            }
        } finally {
            LoadedApkCtorHooker.trackedApks.remove(loadedApk)
        }

        return result
    }
}
