package org.matrix.vector.impl.hookers

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.RequiresApi
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

/** Centralized helper for determining context details */
private object PackageContextHelper {
    private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }
    private val currentPkgMethod by lazy {
        activityThreadClass.getDeclaredMethod("currentPackageName").apply { isAccessible = true }
    }
    private val currentProcMethod by lazy {
        activityThreadClass.getDeclaredMethod("currentProcessName").apply { isAccessible = true }
    }

    data class ContextInfo(
        val packageName: String,
        val processName: String,
        val isFirstPackage: Boolean,
    )

    fun resolve(loadedApk: Any, apkPackageName: String): ContextInfo {
        var packageName = currentPkgMethod.invoke(null) as? String
        var processName = currentProcMethod.invoke(null) as? String

        val isFirstPackage =
            packageName != null && processName != null && packageName == apkPackageName

        if (!isFirstPackage) {
            packageName = apkPackageName
            processName = currentPkgMethod.invoke(null) as? String ?: apkPackageName
        } else if (packageName == "android") {
            packageName = "system"
        }

        return ContextInfo(packageName!!, processName!!, isFirstPackage)
    }
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
        val isPreload =
            Throwable().stackTrace.any {
                it.className == "android.app.ActivityThread\$ApplicationThread" &&
                    it.methodName == "schedulePreload"
            }

        if (isPreload) {
            return result
        }

        trackedApks.add(loadedApk)
        return result
    }
}

/** Hooking `createAppFactory` is critical to defeating the `<clinit>` evasion exploit. */
@RequiresApi(Build.VERSION_CODES.P)
object LoadedApkCreateAppFactoryHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val loadedApk = chain.thisObject ?: return chain.proceed()

        val appInfo = chain.args[0] as ApplicationInfo
        val defaultClassLoader =
            chain.args[1] as? ClassLoader
                ?: return chain.proceed() // Skip dispatch if there's no ClassLoader
        val apkPackageName = loadedApk.getFieldValue<String>("mPackageName") ?: appInfo.packageName

        val ctx = PackageContextHelper.resolve(loadedApk, apkPackageName)

        // Only dispatch if on API 29+ per libxposed API specification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VectorLifecycleManager.dispatchPackageLoaded(
                ctx.packageName,
                appInfo,
                ctx.isFirstPackage,
                defaultClassLoader,
            )
        }

        return chain.proceed()
    }
}

/**
 * Triggers package ready events immediately after the final Application ClassLoader is created.
 * Also acts as a fallback dispatcher for `onPackageLoaded` for resource-only APKs where
 * `mIncludeCode` is false (meaning `createAppFactory` was never executed).
 */
object LoadedApkCreateCLHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val loadedApk = chain.thisObject ?: return chain.proceed()

        // Fast path exit: Ignore if addedPaths is not null, or untracked
        if (
            chain.args.firstOrNull() != null || !LoadedApkCtorHooker.trackedApks.contains(loadedApk)
        ) {
            return chain.proceed()
        }

        // Proceed with Android's internal ClassLoader creation sequence
        val result = chain.proceed()

        try {
            val apkPackageName = loadedApk.getFieldValue<String>("mPackageName") ?: return result
            val mIncludeCode = loadedApk.getFieldValue<Boolean>("mIncludeCode") ?: true
            val ctx = PackageContextHelper.resolve(loadedApk, apkPackageName)

            if (!ctx.isFirstPackage && !mIncludeCode) return result

            val appInfo =
                loadedApk.getFieldValue<ApplicationInfo>("mApplicationInfo") ?: return result
            val classLoader = loadedApk.getFieldValue<ClassLoader>("mClassLoader") ?: return result
            val defaultClassLoader =
                loadedApk.getFieldValue<ClassLoader>("mDefaultClassLoader") ?: classLoader

            // Dispatch Modern Lifecycle: onPackageReady
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val appComponentFactory = loadedApk.getFieldValue<Any>("mAppComponentFactory")
                VectorLifecycleManager.dispatchPackageReady(
                    ctx.packageName,
                    appInfo,
                    ctx.isFirstPackage,
                    defaultClassLoader,
                    classLoader,
                    appComponentFactory,
                )
            }

            // Dispatch Legacy Lifecycle
            VectorBootstrap.withLegacy { delegate ->
                delegate.onPackageLoaded(
                    LegacyPackageInfo(
                        ctx.packageName,
                        ctx.processName,
                        classLoader,
                        appInfo,
                        ctx.isFirstPackage,
                    )
                )
            }
        } finally {
            LoadedApkCtorHooker.trackedApks.remove(loadedApk)
        }

        return result
    }
}
