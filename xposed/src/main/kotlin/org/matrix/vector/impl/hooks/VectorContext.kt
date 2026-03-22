package org.matrix.vector.impl

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.hooks.VectorCtorInvoker
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.impl.hooks.VectorMethodInvoker
import org.matrix.vector.nativebridge.HookBridge

/**
 * Main framework context implementation. Provides modules with capabilities to hook executables,
 * request invokers, and interact with the system.
 */
class VectorContext(
    private val packageName: String,
    private val applicationInfo: ApplicationInfo,
    private val service: ILSPInjectedModuleService,
) : XposedInterface {

    private val remotePrefs = ConcurrentHashMap<String, SharedPreferences>()

    override fun getFrameworkName(): String = "Vector"

    override fun getFrameworkVersion(): String = "1.0.0"

    override fun getFrameworkVersionCode(): Long = 1L

    override fun getFrameworkProperties(): Long {
        var props = 0L
        // TODO: Dynamically verify capabilities through `service` if required
        props = props or XposedInterface.PROP_CAP_REMOTE
        props = props or XposedInterface.PROP_CAP_SYSTEM
        return props
    }

    override fun hook(origin: Executable): XposedInterface.HookBuilder {
        return VectorHookBuilder(origin)
    }

    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder {
        val clinit =
            HookBridge.getStaticInitializer(origin)
                ?: throw IllegalArgumentException("Class ${origin.name} has no static initializer")
        return VectorHookBuilder(clinit)
    }

    override fun deoptimize(executable: Executable): Boolean {
        return HookBridge.deoptimizeMethod(executable)
    }

    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> {
        return VectorMethodInvoker(method)
    }

    override fun <T : Any> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> {
        return VectorCtorInvoker(constructor)
    }

    override fun getModuleApplicationInfo(): ApplicationInfo = applicationInfo

    override fun getRemotePreferences(name: String): SharedPreferences {
        return remotePrefs.getOrPut(name) { VectorRemotePreferences(service, name) }
    }

    override fun listRemoteFiles(): Array<String> {
        return service.remoteFileList
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        return service.openRemoteFile(name)
            ?: throw FileNotFoundException("Cannot open remote file: $name")
    }

    override fun log(priority: Int, tag: String?, msg: String) {
        log(priority, tag, msg, null)
    }

    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
        val finalTag = tag ?: "VectorContext"
        val prefix = if (packageName.isNotEmpty()) "$packageName: " else ""
        val fullMsg = buildString {
            append(prefix).append(msg)
            if (tr != null) {
                append("\n").append(android.util.Log.getStackTraceString(tr))
            }
        }
        Log.println(priority, finalTag, fullMsg)
    }
}

/** Manages the dispatching of modern lifecycle events to loaded modules. */
object VectorLifecycleManager {

    val activeModules: MutableSet<XposedModule> = ConcurrentHashMap.newKeySet()

    fun dispatchPackageLoaded(
        packageName: String,
        appInfo: ApplicationInfo,
        isFirst: Boolean,
        defaultClassLoader: ClassLoader,
    ) {
        val param =
            object : PackageLoadedParam {
                override fun getPackageName(): String = packageName

                override fun getApplicationInfo(): ApplicationInfo = appInfo

                override fun isFirstPackage(): Boolean = isFirst

                override fun getDefaultClassLoader(): ClassLoader = defaultClassLoader
            }

        activeModules.forEach { module ->
            runCatching { module.onPackageLoaded(param) }
                .onFailure {
                    Log.e(
                        "VectorLifecycle",
                        "Error in onPackageLoaded for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }

    fun dispatchPackageReady(
        packageName: String,
        appInfo: ApplicationInfo,
        isFirst: Boolean,
        defaultClassLoader: ClassLoader,
        classLoader: ClassLoader,
        appComponentFactory: Any, // Abstracted for API compatibility
    ) {
        val param =
            object : PackageReadyParam {
                override fun getPackageName(): String = packageName

                override fun getApplicationInfo(): ApplicationInfo = appInfo

                override fun isFirstPackage(): Boolean = isFirst

                override fun getDefaultClassLoader(): ClassLoader = defaultClassLoader

                override fun getClassLoader(): ClassLoader = classLoader

                @Suppress("NewApi")
                override fun getAppComponentFactory(): android.app.AppComponentFactory {
                    return appComponentFactory as android.app.AppComponentFactory
                }
            }

        activeModules.forEach { module ->
            runCatching { module.onPackageReady(param) }
                .onFailure {
                    Log.e(
                        "VectorLifecycle",
                        "Error in onPackageReady for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }

    fun dispatchSystemServerStarting(classLoader: ClassLoader) {
        val param =
            object : SystemServerStartingParam {
                override fun getClassLoader(): ClassLoader = classLoader
            }

        activeModules.forEach { module ->
            runCatching { module.onSystemServerStarting(param) }
                .onFailure {
                    Log.e(
                        "VectorLifecycle",
                        "Error in onSystemServerStarting for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }
}
